package com.chrisgoac.gymlog.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A global catalog exercise, packaged in the APK (`exercises-v1.json`) and imported locally.
 *
 * **Local-only on purpose**: not part of the sync registry, never written through the outbox and
 * never pushed or pulled. Ids keep the PWA's `seed-...` form. Custom exercises stay in the
 * synchronized `exercises` table; repositories merge both sources.
 */
@Entity(tableName = "catalog_exercises", indices = [Index("grupoMuscular"), Index("nombre")])
data class CatalogExerciseEntity(
    @PrimaryKey val id: String,
    val nombre: String,
    val grupoMuscular: String,
    val equipamiento: String,
    val tipo: String,
    val videoUrl: String?,
    val descripcion: String?,
    val execution: String?,
)

/** Single-row table (id = 0): the catalog asset version imported into this user database. */
@Entity(tableName = "catalog_meta")
data class CatalogMetaEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val importedVersion: Int,
)
