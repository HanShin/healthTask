package com.hanshin.healthtask.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        ExerciseEntity::class,
        RoutineEntity::class,
        RoutineItemEntity::class,
        WorkoutSessionEntity::class,
        WorkoutItemEntity::class,
        SetRecordEntity::class,
        HealthMeasurementEntity::class,
        SamsungWorkoutLinkEntity::class,
        SyncStateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HealthTaskDatabase : RoomDatabase() {
    abstract fun dao(): HealthTaskDao

    companion object {
        fun create(context: Context): HealthTaskDatabase =
            Room.databaseBuilder(context, HealthTaskDatabase::class.java, "health-task.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN averageHeartRateBpm REAL")
            }
        }
    }
}
