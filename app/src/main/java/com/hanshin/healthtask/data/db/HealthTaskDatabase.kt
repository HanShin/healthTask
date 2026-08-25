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
        TrainingPlanEntity::class,
        PlanSlotEntity::class,
        WorkoutSessionEntity::class,
        WorkoutItemEntity::class,
        SetRecordEntity::class,
        HealthMeasurementEntity::class,
        SamsungWorkoutLinkEntity::class,
        SyncStateEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HealthTaskDatabase : RoomDatabase() {
    abstract fun dao(): HealthTaskDao

    companion object {
        fun create(context: Context): HealthTaskDatabase =
            Room.databaseBuilder(context, HealthTaskDatabase::class.java, "health-task.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN averageHeartRateBpm REAL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN routePolyline TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN lapData TEXT")
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN activeDurationMillis INTEGER")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `training_plans` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `goalType` TEXT NOT NULL, `isActive` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_training_plans_isActive` ON `training_plans` (`isActive`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plan_slots` (`id` TEXT NOT NULL, `planId` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, `workoutType` TEXT NOT NULL, `routineId` TEXT, `title` TEXT NOT NULL, `preferredDayOfWeek` INTEGER, `targetDurationMin` REAL, `targetDistanceKm` REAL, `targetPaceMinPerKm` REAL, `note` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_slots_planId` ON `plan_slots` (`planId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_slots_routineId` ON `plan_slots` (`routineId`)")
                db.execSQL("ALTER TABLE `workout_sessions` ADD COLUMN `planSlotId` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_workout_sessions_planSlotId` ON `workout_sessions` (`planSlotId`)")
            }
        }
    }
}
