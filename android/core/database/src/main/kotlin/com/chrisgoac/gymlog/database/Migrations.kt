package com.chrisgoac.gymlog.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Schema 1 -> 2: adds the local-only global catalog tables. Synchronized tables are untouched. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `catalog_exercises` (`id` TEXT NOT NULL, `nombre` TEXT NOT NULL, " +
                "`grupoMuscular` TEXT NOT NULL, `equipamiento` TEXT NOT NULL, `tipo` TEXT NOT NULL, " +
                "`videoUrl` TEXT, `descripcion` TEXT, `execution` TEXT, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_exercises_grupoMuscular` ON `catalog_exercises` (`grupoMuscular`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_catalog_exercises_nombre` ON `catalog_exercises` (`nombre`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `catalog_meta` (`id` INTEGER NOT NULL, `importedVersion` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

/** Every migration of [GymLogDatabase], in order. */
val GYMLOG_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
