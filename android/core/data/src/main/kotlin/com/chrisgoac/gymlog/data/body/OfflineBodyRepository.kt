package com.chrisgoac.gymlog.data.body

import com.chrisgoac.gymlog.domain.settings.SettingsCodec
import android.util.Log
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.LocalMutationScope
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.BodyMetricEntity
import com.chrisgoac.gymlog.domain.body.BodyDateError
import com.chrisgoac.gymlog.domain.body.BodyMeasurement
import com.chrisgoac.gymlog.domain.body.BodyMeasurementMissingException
import com.chrisgoac.gymlog.domain.body.BodyMetrics
import com.chrisgoac.gymlog.domain.body.BodyProblem
import com.chrisgoac.gymlog.domain.body.BodyRepository
import com.chrisgoac.gymlog.domain.body.BodyValidation
import com.chrisgoac.gymlog.domain.body.CustomBodyMetric
import com.chrisgoac.gymlog.domain.body.CustomMetricCheck
import com.chrisgoac.gymlog.domain.body.CustomMetricStoreUnreadableException
import com.chrisgoac.gymlog.domain.body.InvalidBodyMeasurementException
import com.chrisgoac.gymlog.domain.body.InvalidCustomMetricException
import com.chrisgoac.gymlog.model.BodyMetricRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Body measurements over the open account's Room database. Reads are scoped queries on the
 * (tipo, fecha) index; every write is one [LocalMutation] validated first. Custom metrics are the
 * PWA's synced setting row `metricasPersonalizadas` (JSON array of `{clave, label, unidad}`), read
 * and appended in the same format, so the PWA renders what Android creates and vice versa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineBodyRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
    private val now: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : BodyRepository {

    override fun observeCustomMetrics(): Flow<List<CustomBodyMetric>> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(emptyList())
        } else {
            open.database.userSettingDao().observeLive()
                .map { rows -> parseCustom(rows.firstOrNull { it.id == BodyMetrics.CUSTOM_SETTING_KEY }?.valor) }
        }
    }.distinctUntilChanged()

    override fun observeMeasuredTypes(): Flow<Set<String>> = databases.current.flatMapLatest { open ->
        if (open == null) flowOf(emptySet()) else open.database.bodyMetricDao().observeLiveTypes().map { it.toSet() }
    }.distinctUntilChanged()

    override fun observeMeasurements(type: String): Flow<List<BodyMeasurement>> = databases.current.flatMapLatest { open ->
        if (open == null) flowOf(emptyList()) else open.database.bodyMetricDao().observeLiveByType(type).map { rows -> rows.map { it.toMeasurement() } }
    }.distinctUntilChanged()

    override suspend fun addMeasurement(type: String, value: Double, at: Long): String {
        checkDate(at)
        return mutate {
            val custom = customMetrics()
            if (!BodyMetrics.isKnown(type, custom)) throw InvalidBodyMeasurementException(BodyProblem.UNKNOWN_METRIC)
            checkValue(BodyMetrics.resolve(type, custom), value)
            val id = RecordId(newId())
            put(BodyMetricRecord(id, nextTimestamp(SyncTable.BODY_METRICS, id), null, type, value, at))
            id.value
        }
    }

    override suspend fun updateMeasurement(id: String, value: Double, at: Long) {
        mutate {
            val row = live(id) ?: throw BodyMeasurementMissingException(id)
            if (at != row.fecha) checkDate(at)
            if (value != row.valor) checkValue(BodyMetrics.resolve(row.tipo, customMetrics()), value)
            if (row.valor == value && row.fecha == at) return@mutate
            put(row.copy(valor = value, fecha = at, updatedAt = nextTimestamp(SyncTable.BODY_METRICS, row.id)))
        }
    }

    override suspend fun deleteMeasurement(id: String) = mutate {
        val row = live(id) ?: return@mutate
        val stamp = nextTimestamp(SyncTable.BODY_METRICS, row.id)
        put(row.copy(updatedAt = stamp, deletedAt = stamp))
    }

    override suspend fun addCustomMetric(name: String, unit: String): CustomBodyMetric = mutate {
        val settingId = RecordId(BodyMetrics.CUSTOM_SETTING_KEY)
        val stored = find(SyncTable.USER_SETTINGS, settingId) as? UserSettingRecord
        val liveStored = stored?.takeIf { it.deletedAt == null }
        when (val check = BodyMetrics.checkCustom(name, unit, parseCustom(liveStored?.valor))) {
            is CustomMetricCheck.Invalid -> throw InvalidCustomMetricException(check.problem)
            is CustomMetricCheck.Existing -> check.metric
            is CustomMetricCheck.New -> {
                // Append to the stored array untouched so entries/fields this version does not know survive.
                val stored = liveStored?.valor?.takeIf { it.isNotBlank() }
                val array = if (stored == null) JsonArray(emptyList()) else parseArray(stored) ?: run {
                    Log.w("OfflineBodyRepository", "metricasPersonalizadas is not a JSON array; refusing to overwrite it")
                    throw CustomMetricStoreUnreadableException()
                }
                val items = array.toMutableList()
                items += JsonObject(
                    mapOf(
                        "clave" to JsonPrimitive(check.metric.key),
                        "label" to JsonPrimitive(check.metric.label),
                        "unidad" to JsonPrimitive(check.metric.unit),
                    ),
                )
                put(
                    UserSettingRecord(
                        id = settingId,
                        updatedAt = nextTimestamp(SyncTable.USER_SETTINGS, settingId),
                        deletedAt = null,
                        valor = JsonArray(items).toString(),
                    ),
                )
                check.metric
            }
        }
    }

    // ---- Helpers ----

    private fun checkDate(at: Long) {
        when (BodyValidation.checkDate(at, now())) {
            BodyDateError.IN_FUTURE -> throw InvalidBodyMeasurementException(BodyProblem.DATE_IN_FUTURE)
            BodyDateError.TOO_OLD -> throw InvalidBodyMeasurementException(BodyProblem.DATE_TOO_OLD)
            null -> Unit
        }
    }

    private fun checkValue(metric: com.chrisgoac.gymlog.domain.body.BodyMetricDef, value: Double) {
        if (!BodyValidation.isStorable(metric, value)) throw InvalidBodyMeasurementException(BodyProblem.VALUE_OUT_OF_RANGE)
    }

    private suspend fun <T> mutate(block: suspend LocalMutationScope.() -> T): T {
        val database = checkNotNull(databases.current.value) { "No account database is open" }.database
        return runnerFor(database).run(LocalMutation { block() })
    }

    private suspend fun LocalMutationScope.live(id: String): BodyMetricRecord? =
        (find(SyncTable.BODY_METRICS, RecordId(id)) as? BodyMetricRecord)?.takeIf { it.deletedAt == null }

    private suspend fun LocalMutationScope.customMetrics(): List<CustomBodyMetric> =
        parseCustom((find(SyncTable.USER_SETTINGS, RecordId(BodyMetrics.CUSTOM_SETTING_KEY)) as? UserSettingRecord)?.takeIf { it.deletedAt == null }?.valor)

    private fun BodyMetricEntity.toMeasurement() = BodyMeasurement(id, tipo, valor, fecha, updatedAt)

    private fun parseArray(text: String): JsonArray? = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()

    /** Tolerant read through the shared codec: invalid JSON or malformed entries are skipped, never thrown. */
    private fun parseCustom(text: String?): List<CustomBodyMetric> = SettingsCodec.customMetrics(text)

}
