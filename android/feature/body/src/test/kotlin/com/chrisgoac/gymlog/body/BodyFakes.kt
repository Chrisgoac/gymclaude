package com.chrisgoac.gymlog.body

import com.chrisgoac.gymlog.domain.body.BodyMeasurement
import com.chrisgoac.gymlog.domain.body.BodyMeasurementMissingException
import com.chrisgoac.gymlog.domain.body.BodyMetrics
import com.chrisgoac.gymlog.domain.body.BodyRepository
import com.chrisgoac.gymlog.domain.body.CustomBodyMetric
import com.chrisgoac.gymlog.domain.body.CustomMetricCheck
import com.chrisgoac.gymlog.domain.body.InvalidCustomMetricException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [BodyRepository] with the real ordering and validation entry points; records every write. */
class FakeBodyRepository : BodyRepository {
    val rows = MutableStateFlow<List<BodyMeasurement>>(emptyList())
    val customs = MutableStateFlow<List<CustomBodyMetric>>(emptyList())
    val writes = mutableListOf<String>()
    var failWrites = false
    var failReads: Throwable? = null
    private var clock = 1L
    private var seq = 0

    fun seed(type: String, value: Double, at: Long): String {
        val id = "m${++seq}"
        rows.value = rows.value + BodyMeasurement(id, type, value, at, ++clock)
        return id
    }

    override fun observeCustomMetrics(): Flow<List<CustomBodyMetric>> = customs
    override fun observeMeasuredTypes(): Flow<Set<String>> = rows.map { list -> list.map { it.type }.toSet() }
    override fun observeMeasurements(type: String): Flow<List<BodyMeasurement>> = rows.map { list ->
        failReads?.let { throw it }
        list.filter { it.type == type }.sortedWith(compareBy({ it.at }, { it.updatedAt }, { it.id }))
    }

    override suspend fun addMeasurement(type: String, value: Double, at: Long): String {
        check(!failWrites) { "disk full" }
        writes += "add:$type:$value:$at"
        return seed(type, value, at)
    }

    override suspend fun updateMeasurement(id: String, value: Double, at: Long) {
        check(!failWrites) { "disk full" }
        val row = rows.value.firstOrNull { it.id == id } ?: throw BodyMeasurementMissingException(id)
        writes += "update:$id:$value:$at"
        rows.value = rows.value.map { if (it.id == id) it.copy(value = value, at = at, updatedAt = ++clock) else it }
    }

    override suspend fun deleteMeasurement(id: String) {
        check(!failWrites) { "disk full" }
        writes += "delete:$id"
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun addCustomMetric(name: String, unit: String): CustomBodyMetric {
        check(!failWrites) { "disk full" }
        return when (val check = BodyMetrics.checkCustom(name, unit, customs.value)) {
            is CustomMetricCheck.Invalid -> throw InvalidCustomMetricException(check.problem)
            is CustomMetricCheck.Existing -> check.metric
            is CustomMetricCheck.New -> check.metric.also { writes += "custom:${it.key}"; customs.value = customs.value + it }
        }
    }
}
