package com.chrisgoac.gymlog.data.catalog

import androidx.room.withTransaction
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.CatalogMetaEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream

/** One global exercise of the packaged catalog (`exercises-v1.json`, exported from the PWA seed). */
@Serializable
data class CatalogExercise(
    val id: String,
    val nombre: String,
    val grupoMuscular: String,
    val equipamiento: String,
    val tipo: String,
    val videoUrl: String? = null,
    val descripcion: String? = null,
    val execution: String? = null,
)

/** The packaged catalog: [version] is bumped whenever its content changes. */
@Serializable
data class CatalogAsset(
    @SerialName("version") val version: Int,
    @SerialName("exercises") val exercises: List<CatalogExercise>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): CatalogAsset = json.decodeFromString(serializer(), text)

        fun read(stream: InputStream): CatalogAsset = parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}

/**
 * Imports the packaged global catalog into the open user database's local-only
 * `catalog_exercises` table. It never touches the synchronized `exercises` table (custom
 * exercises) and never writes the outbox: catalog rows are not user data.
 */
object CatalogImporter {
    /**
     * Imports [asset] when its version is newer than the one recorded in this database.
     * One transaction upserts the rows and records the version, so a crash leaves either nothing
     * or everything. Returns true when rows were written.
     */
    suspend fun importIfNeeded(database: GymLogDatabase, asset: CatalogAsset): Boolean =
        database.withTransaction {
            val dao = database.catalogDao()
            val imported = dao.importedVersion()
            if (imported != null && imported >= asset.version) return@withTransaction false
            dao.upsertAll(asset.exercises.map { it.toEntity() })
            dao.upsertMeta(CatalogMetaEntity(importedVersion = asset.version))
            true
        }

    private fun CatalogExercise.toEntity() = CatalogExerciseEntity(
        id = id,
        nombre = nombre,
        grupoMuscular = grupoMuscular,
        equipamiento = equipamiento,
        tipo = tipo,
        videoUrl = videoUrl,
        descripcion = descripcion,
        execution = execution,
    )
}
