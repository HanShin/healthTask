import java.util.Properties
import java.security.SecureRandom
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val userHomeDirectory = System.getProperty("user.home")
val externalSigningFile = file(
    System.getenv("HEALTHTASK_SIGNING_PROPERTIES")
        ?: "$userHomeDirectory/.gradle/healthtask-signing.properties"
)
val repositorySigningFile = rootProject.file("keystore.properties")
val activeSigningFile = if (externalSigningFile.exists()) externalSigningFile else repositorySigningFile

android {
    namespace = "com.hanshin.healthtask"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hanshin.healthtask"
        minSdk = 34
        targetSdk = 36
        versionCode = 7
        versionName = "1.3.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

    signingConfigs {
        if (activeSigningFile.exists()) {
            create("release") {
                val properties = Properties().apply {
                    activeSigningFile.inputStream().use { load(it) }
                }
                storeFile = file(properties.getProperty("storeFile"))
                storePassword = properties.getProperty("storePassword")
                keyAlias = properties.getProperty("keyAlias")
                keyPassword = properties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}

tasks.register("generatePersonalReleaseKey") {
    group = "build setup"
    description = "Creates the personal 오늘운동 release key outside the repository without overwriting an existing key."
    doLast {
        val keyStore = file("$userHomeDirectory/.android/healthtask-release.jks")
        check(!keyStore.exists()) { "Release key already exists: ${keyStore.absolutePath}" }
        check(!externalSigningFile.exists()) { "Signing properties already exist: ${externalSigningFile.absolutePath}" }

        keyStore.parentFile.mkdirs()
        externalSigningFile.parentFile.mkdirs()
        val random = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val password = random.joinToString("") { "%02x".format(it) }
        val keytool = file("${System.getProperty("java.home")}/bin/keytool")
        val process = ProcessBuilder(
            keytool.absolutePath,
            "-genkeypair",
            "-keystore", keyStore.absolutePath,
            "-storetype", "PKCS12",
            "-storepass", password,
            "-keypass", password,
            "-alias", "healthtask",
            "-keyalg", "RSA",
            "-keysize", "4096",
            "-validity", "10000",
            "-dname", "CN=Han Shin, OU=Personal, O=Today Workout, L=Seoul, C=KR",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "keytool failed: $output" }

        externalSigningFile.writeText(
            "storeFile=${keyStore.absolutePath}\n" +
                "storePassword=$password\n" +
                "keyAlias=healthtask\n" +
                "keyPassword=$password\n"
        )
        runCatching {
            Files.setPosixFilePermissions(keyStore.toPath(), PosixFilePermissions.fromString("rw-------"))
            Files.setPosixFilePermissions(externalSigningFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        logger.lifecycle("Created personal release key: ${keyStore.absolutePath}")
        logger.lifecycle("Created signing configuration: ${externalSigningFile.absolutePath}")
        logger.lifecycle("Back up both files securely; losing them prevents signed updates.")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":shared"))
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("androidx.wear:wear-remote-interactions:1.2.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
