package com.hanshin.healthtask.cloud

import android.content.Context
import android.os.Build
import androidx.work.*
import androidx.room.withTransaction
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hanshin.healthtask.HealthTaskApplication
import com.hanshin.healthtask.data.db.SyncStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

class CloudHttpException(val status: Int) : IOException("클라우드 요청 오류 ($status)")

class CloudSync(private val app: HealthTaskApplication) {
    private val credentials = CloudCredentials(app)
    private val mutex = Mutex()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val preferences = app.getSharedPreferences("cloud_state", Context.MODE_PRIVATE)

    fun status(): String = when {
        runCatching { credentials.read() }.getOrNull() == null -> "연결 안 됨"
        preferences.getString("error", null) != null -> "연결됨 · ${preferences.getString("error", "") }"
        preferences.getString("health_permission_warning", null) != null -> "연결됨 · 수면·걸음·심박 일부 권한 부족 · 해당 항목 전송 일시 중지"
        else -> "연결됨 · 최근 전송 ${preferences.getString("last_success", "아직 없음") }"
    }

    suspend fun pair(code: String) = mutex.withLock {
        require(code.trim().matches(Regex("[a-f0-9]{32}"))) { "웹의 32자리 연결 코드를 입력하세요." }
        check(credentials.read() == null) { "기존 연결을 해제한 후 다시 연결하세요." }
        val response = post("device-pair", JsonObject().apply {
            addProperty("action", "claim"); addProperty("code", code.trim()); addProperty("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
        }, null)
        // A new device must upload everything, even after a previous pairing was removed.
        app.database.withTransaction {
            app.database.dao().upsertSyncState(SyncStateEntity(MANIFEST, "{}"))
            app.database.dao().deleteSyncState(PENDING)
        }
        credentials.save(response)
        preferences.edit().remove("error").remove("last_success").apply()
        schedule(app)
    }

    suspend fun disconnect() = mutex.withLock {
        val credential = credentials.read() ?: return@withLock
        try {
            post("device-pair", JsonObject().apply { addProperty("action", "revoke-self") }, credential.get("token").asString)
        } catch (error: CloudHttpException) {
            // Credentials already revoked on the web can be removed locally.
            if (error.status != 401 && error.status != 403) throw error
        }
        credentials.clear()
        WorkManager.getInstance(app).cancelUniqueWork(IMMEDIATE)
        WorkManager.getInstance(app).cancelUniqueWork(PERIODIC)
        preferences.edit().clear().apply()
    }

    suspend fun upload() = mutex.withLock {
        val credential = credentials.read() ?: return@withLock
        val dao = app.database.dao()
        val root = JsonParser.parseString(app.backupCodec.export()).asJsonObject
        val cached = dao.getCloudHealthRecords().map { JsonParser.parseString(it.value).asJsonObject }
        val allowed = runCatching { app.healthConnect.cloudAllowedMetrics() }.getOrDefault(emptySet())
        val held = cached.filter { it.getAsJsonObject("data").get("metric").asString !in allowed }
        val heldIds = held.map { it.get("source").asString + ":" + it.get("externalId").asString }
        if (held.isNotEmpty()) preferences.edit().putString("health_permission_warning", "partial").apply()
        else preferences.edit().remove("health_permission_warning").apply()
        val raw = CloudRecords.fromExport(root) + cached.filter { it !in held }
        val target = raw.flatMap { CloudRecords.split(it) }
        val previous = JsonParser.parseString(dao.getSyncState(MANIFEST)?.value ?: "{}").asJsonObject
        fun identity(record: JsonObject) = record.get("source").asString + ":" + record.get("externalId").asString
        suspend fun transmit(payload: JsonObject) {
            dao.upsertSyncState(SyncStateEntity(PENDING, payload.toString()))
            post("health-ingest", payload, credential.get("token").asString)
            payload.getAsJsonArray("records").forEach { item ->
                val record = item.asJsonObject
                val id = identity(record)
                if (record.get("deleted")?.asBoolean == true) previous.remove(id)
                else previous.add(id, JsonObject().apply {
                    addProperty("hash", CloudRecords.hash(record.toString()))
                    add("identity", record.deepCopy().apply { remove("data") })
                })
            }
            app.database.withTransaction {
                dao.upsertSyncState(SyncStateEntity(MANIFEST, previous.toString()))
                dao.deleteSyncState(PENDING)
            }
        }
        // Replay the exact persisted payload and batch ID after an uncertain response or process death.
        dao.getSyncState(PENDING)?.let { pending ->
            val payload = JsonParser.parseString(pending.value).asJsonObject
            // A permission revocation must stop new delivery of the withheld Health Connect records.
            val safe = payload.getAsJsonArray("records").all { item ->
                val id = identity(item.asJsonObject)
                heldIds.none { id == it || id.startsWith(it + ":part:") }
            }
            if (safe) transmit(payload) else dao.deleteSyncState(PENDING)
        }
        val targetIds = target.mapTo(hashSetOf(), ::identity)
        val changed = target.filter { previous.getAsJsonObject(identity(it))?.get("hash")?.asString != CloudRecords.hash(it.toString()) }
        val deleted = previous.entrySet().filter { entry -> entry.key !in targetIds && heldIds.none { entry.key == it || entry.key.startsWith(it + ":part:") } }.map { (_, value) ->
            value.asJsonObject.getAsJsonObject("identity").deepCopy().apply {
                addProperty("deleted", true); addProperty("updatedAt", Instant.now().toString()); add("data", JsonObject())
            }
        }
        // Explicit tombstones only refer to IDs this installation previously uploaded successfully.
        // Failed reads never produce a snapshot, so they cannot delete remote records.
        CloudRecords.batches(changed + deleted).forEach { batch ->
            transmit(JsonObject().apply {
                addProperty("version", 1); addProperty("batchId", CloudRecords.batchId(batch))
                add("records", JsonArray().apply { batch.forEach { add(it) } })
            })
        }
        preferences.edit().putString("last_success", Instant.now().toString()).remove("error").apply()
    }

    fun recordError(message: String) { preferences.edit().putString("error", message).apply() }

    private suspend fun post(path: String, payload: JsonObject, bearer: String?): JsonObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("https://hykgvrbsvldrjdoxxlnl.supabase.co/functions/v1/$path")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw CloudHttpException(response.code)
            JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
        }
    }

    companion object {
        private const val PENDING = "cloud_pending_batch_v1"
        private const val MANIFEST = "cloud_uploaded_manifest_v1"
        private const val IMMEDIATE = "health-cloud-upload"
        private const val PERIODIC = "health-cloud-periodic"
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CloudUploadWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build())
            enqueue(context)
        }
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CloudUploadWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}

class CloudUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as HealthTaskApplication).cloudSync
        return try { sync.upload(); Result.success() }
        catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            sync.recordError(when (error) {
                is CloudHttpException -> if (error.status == 401 || error.status == 403) "인증 또는 기기 권한을 확인하세요." else error.message ?: "전송 실패"
                else -> "전송 실패 · 네트워크와 연결 설정을 확인하세요."
            })
            if (error is CloudHttpException && error.status in 400..499 && error.status != 429 && error.status != 409) Result.failure() else Result.retry()
        }
    }
}
