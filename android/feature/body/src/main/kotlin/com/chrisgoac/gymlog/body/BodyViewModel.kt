package com.chrisgoac.gymlog.body

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.body.BodyDateError
import com.chrisgoac.gymlog.domain.body.BodyMeasurement
import com.chrisgoac.gymlog.domain.body.BodyMeasurementMissingException
import com.chrisgoac.gymlog.domain.body.BodyMetricDef
import com.chrisgoac.gymlog.domain.body.BodyMetrics
import com.chrisgoac.gymlog.domain.body.BodyParse
import com.chrisgoac.gymlog.domain.body.BodyProblem
import com.chrisgoac.gymlog.domain.body.BodyRepository
import com.chrisgoac.gymlog.domain.body.BodyValidation
import com.chrisgoac.gymlog.domain.body.BodyValueError
import com.chrisgoac.gymlog.domain.body.CustomBodyMetric
import com.chrisgoac.gymlog.domain.body.CustomMetricProblem
import com.chrisgoac.gymlog.domain.body.InvalidBodyMeasurementException
import com.chrisgoac.gymlog.domain.body.InvalidCustomMetricException
import com.chrisgoac.gymlog.domain.math.BodyEntry
import com.chrisgoac.gymlog.domain.math.BodySummary
import com.chrisgoac.gymlog.domain.math.WeightUnit
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.domain.time.Periods
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val STOP_TIMEOUT_MILLIS = 5_000L
private const val KEY_METRIC = "metric"
private const val KEY_PERIOD = "period"

enum class BodyStatus { Loading, Ready, Failed }

/** Server-corrected clock and device zone the screen is computed with. */
data class BodyClock(val nowMillis: Long, val zone: ZoneId) {
    val today: LocalDate get() = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
}

data class MetricOption(val def: BodyMetricDef, val hasData: Boolean)

/**
 * Latest reading and how it moved. Deltas are storage-unit differences (kg / cm / custom):
 * [previousDelta] against the reading before it, [periodDelta] against the first reading of the
 * selected period (null with fewer than two readings in it, like `resumenSerie`).
 */
data class BodySummaryUi(
    val latest: BodyMeasurement,
    val previousDelta: Double?,
    val periodFirst: Double?,
    val periodDelta: Double?,
)

sealed interface EditorProblem {
    data class Value(val error: BodyValueError, val min: Double?, val max: Double?) : EditorProblem
    data class Date(val error: BodyDateError) : EditorProblem
    /** The row was deleted elsewhere while editing. */
    data object Missing : EditorProblem
    data object SaveFailed : EditorProblem
}

data class BodyEditorState(
    /** Null creates a measurement. */
    val entryId: String?,
    val originalAt: Long?,
    val valueText: String,
    val date: LocalDate,
    val problem: EditorProblem? = null,
    val saving: Boolean = false,
)

data class CustomMetricDialogState(
    val name: String = "",
    val unit: String = "",
    val problem: CustomMetricProblem? = null,
    val failed: Boolean = false,
    val saving: Boolean = false,
)

data class BodyUiState(
    val status: BodyStatus = BodyStatus.Loading,
    /** Starts as the predefined set so the chip row never mounts empty (a lazy row would anchor its scroll to the last chip once the rest appear). */
    val options: List<MetricOption> = BodyMetrics.predefined.map { MetricOption(it, hasData = false) },
    val selected: BodyMetricDef = BodyMetrics.predefined.first(),
    val period: Period = Period.THREE_MONTHS,
    val weightUnit: WeightUnit = WeightUnit.KG,
    /** The selected period, oldest first: what the chart and the period delta use. */
    val series: List<BodyMeasurement> = emptyList(),
    /** Every live reading of the metric, newest first: the history list. */
    val history: List<BodyMeasurement> = emptyList(),
    val summary: BodySummaryUi? = null,
    val clock: BodyClock? = null,
    val editor: BodyEditorState? = null,
    val customDialog: CustomMetricDialogState? = null,
    val pendingDelete: BodyMeasurement? = null,
    val actionFailed: Boolean = false,
)

private data class LocalUi(
    val editor: BodyEditorState? = null,
    val customDialog: CustomMetricDialogState? = null,
    val pendingDelete: BodyMeasurement? = null,
    val actionFailed: Boolean = false,
)

private data class Content(val status: BodyStatus, val entries: List<BodyMeasurement>)

/**
 * Body measurements: metric selector, summary, chart series and history for the chosen metric,
 * plus the editor / custom-metric / delete-confirmation state. The metric and period survive
 * process death in [savedState]. Period changes recompute in memory (no reload, no flash);
 * [weightUnit] is the display hook for Task 6's synced units setting (kilograms until then).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BodyViewModel(
    private val repository: BodyRepository,
    private val savedState: SavedStateHandle,
    private val clock: () -> BodyClock,
    private val weightUnit: StateFlow<WeightUnit> = MutableStateFlow(WeightUnit.KG),
) : ViewModel() {
    private val rawMetric = MutableStateFlow(savedState.get<String>(KEY_METRIC) ?: BodyMetrics.WEIGHT)
    private val period = MutableStateFlow(savedState.get<String>(KEY_PERIOD)?.let(Period::fromWire) ?: Period.THREE_MONTHS)
    private val attempt = MutableStateFlow(0)
    private val local = MutableStateFlow(LocalUi())

    private data class Catalog(val customs: List<CustomBodyMetric>, val options: List<MetricOption>)

    private val catalog: Flow<Catalog> = combine(
        repository.observeCustomMetrics().catch { emit(emptyList()) },
        repository.observeMeasuredTypes().catch { emit(emptySet()) },
    ) { customs, measured ->
        val known = BodyMetrics.predefined.map { it.key } + customs.map { it.key }
        val unknown = measured.filter { it !in known }.sorted()
        val defs = BodyMetrics.predefined + customs.map(BodyMetrics::custom) + unknown.map { BodyMetrics.resolve(it, customs) }
        Catalog(customs, defs.map { MetricOption(it, it.key in measured) })
    }

    /** The selected metric, falling back to weight if it vanished (e.g. its custom definition was removed elsewhere). */
    private val selected: Flow<BodyMetricDef> = combine(rawMetric, catalog) { key, c ->
        c.options.firstOrNull { it.def.key == key }?.def ?: c.options.first().def
    }.distinctUntilChanged()

    private val content: Flow<Content> = combine(selected, attempt) { def, _ -> def }
        .flatMapLatest { def ->
            repository.observeMeasurements(def.key)
                .map { Content(BodyStatus.Ready, it) }
                .onStart { emit(Content(BodyStatus.Loading, emptyList())) }
                .catch { emit(Content(BodyStatus.Failed, emptyList())) }
        }

    private data class Inputs(val catalog: Catalog, val selected: BodyMetricDef, val period: Period, val unit: WeightUnit)

    private val inputs: Flow<Inputs> = combine(catalog, selected, period, weightUnit) { c, s, p, u -> Inputs(c, s, p, u) }

    val state: StateFlow<BodyUiState> = combine(content, inputs, local) { c, i, l ->
        val now = clock()
        val series = c.entries.filter { it.at >= Periods.since(i.period, Instant.ofEpochMilli(now.nowMillis)).toEpochMilli() }
        BodyUiState(
            status = c.status,
            options = i.catalog.options,
            selected = i.selected,
            period = i.period,
            weightUnit = i.unit,
            series = series,
            history = c.entries.asReversed(),
            summary = summary(c.entries, series, now.zone),
            clock = now,
            editor = l.editor,
            customDialog = l.customDialog,
            pendingDelete = l.pendingDelete,
            actionFailed = l.actionFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BodyUiState(period = period.value))

    private fun summary(all: List<BodyMeasurement>, series: List<BodyMeasurement>, zone: ZoneId): BodySummaryUi? {
        val latest = all.lastOrNull() ?: return null
        val periodSummary = BodySummary.of(series.map { BodyEntry(it.value, Instant.ofEpochMilli(it.at)) }, zone)
        return BodySummaryUi(
            latest = latest,
            previousDelta = all.getOrNull(all.size - 2)?.let { latest.value - it.value },
            periodFirst = periodSummary.first,
            periodDelta = periodSummary.delta,
        )
    }

    // ---- Selection ----

    fun onMetric(key: String) {
        savedState[KEY_METRIC] = key
        rawMetric.value = key
        local.update { it.copy(editor = null) }
    }

    fun onPeriod(value: Period) {
        savedState[KEY_PERIOD] = value.wire
        period.value = value
    }

    fun retry() = attempt.update { it + 1 }

    // ---- Editor ----

    fun onAdd() {
        local.update { it.copy(editor = BodyEditorState(entryId = null, originalAt = null, valueText = "", date = clock().today), actionFailed = false) }
    }

    fun onEdit(measurement: BodyMeasurement) {
        val now = clock()
        val shown = state.value.selected.let { if (it.isWeight) weightUnit.value.fromKg(measurement.value) else measurement.value }
        local.update {
            it.copy(
                editor = BodyEditorState(
                    entryId = measurement.id,
                    originalAt = measurement.at,
                    valueText = BodyFormat.input(shown),
                    date = Instant.ofEpochMilli(measurement.at).atZone(now.zone).toLocalDate(),
                ),
                actionFailed = false,
            )
        }
    }

    fun onEditorValue(text: String) = updateEditor { it.copy(valueText = text, problem = null) }

    fun onEditorDate(date: LocalDate) = updateEditor { it.copy(date = date, problem = null) }

    fun onEditorCancel() = local.update { it.copy(editor = null) }

    private fun updateEditor(change: (BodyEditorState) -> BodyEditorState) =
        local.update { ui -> ui.copy(editor = ui.editor?.let(change)) }

    fun onSave() {
        val editor = local.value.editor ?: return
        if (editor.saving) return
        val def = state.value.selected
        val now = clock()
        val parsed = BodyValidation.parseValue(editor.valueText, def, weightUnit.value)
        if (parsed is BodyParse.Invalid) return updateEditor { it.copy(problem = EditorProblem.Value(parsed.error, parsed.min, parsed.max)) }
        val at = storedInstant(editor, now)
        val dateError = if (editor.date.isAfter(now.today)) BodyDateError.IN_FUTURE else BodyValidation.checkDate(at, now.nowMillis)
        if (dateError != null) return updateEditor { it.copy(problem = EditorProblem.Date(dateError)) }
        val value = (parsed as BodyParse.Valid).storage
        updateEditor { it.copy(saving = true, problem = null) }
        viewModelScope.launch {
            try {
                if (editor.entryId == null) repository.addMeasurement(def.key, value, at) else repository.updateMeasurement(editor.entryId, value, at)
                local.update { it.copy(editor = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                updateEditor { it.copy(saving = false, problem = problemOf(e)) }
            }
        }
    }

    /** Today keeps the real time (so same-day entries order naturally); other days sit at local midnight like the PWA; an unchanged day keeps its original instant. */
    private fun storedInstant(editor: BodyEditorState, now: BodyClock): Long {
        val original = editor.originalAt
        return when {
            original != null && Instant.ofEpochMilli(original).atZone(now.zone).toLocalDate() == editor.date -> original
            editor.date == now.today -> now.nowMillis
            else -> editor.date.atStartOfDay(now.zone).toInstant().toEpochMilli()
        }
    }

    private fun problemOf(e: Exception): EditorProblem = when {
        e is BodyMeasurementMissingException -> EditorProblem.Missing
        e is InvalidBodyMeasurementException && e.problem == BodyProblem.DATE_IN_FUTURE -> EditorProblem.Date(BodyDateError.IN_FUTURE)
        e is InvalidBodyMeasurementException && e.problem == BodyProblem.DATE_TOO_OLD -> EditorProblem.Date(BodyDateError.TOO_OLD)
        else -> EditorProblem.SaveFailed
    }

    // ---- Delete ----

    fun onDelete(measurement: BodyMeasurement) = local.update { it.copy(pendingDelete = measurement, actionFailed = false) }

    fun onCancelDelete() = local.update { it.copy(pendingDelete = null) }

    fun onConfirmDelete() {
        val target = local.value.pendingDelete ?: return
        local.update { it.copy(pendingDelete = null) }
        viewModelScope.launch {
            try {
                repository.deleteMeasurement(target.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                local.update { it.copy(actionFailed = true) }
            }
        }
    }

    fun onDismissFailure() = local.update { it.copy(actionFailed = false) }

    // ---- Custom metrics ----

    fun onNewMetric() = local.update { it.copy(customDialog = CustomMetricDialogState()) }

    fun onCustomName(text: String) = updateDialog { it.copy(name = text, problem = null, failed = false) }

    fun onCustomUnit(text: String) = updateDialog { it.copy(unit = text, problem = null, failed = false) }

    fun onCustomCancel() = local.update { it.copy(customDialog = null) }

    private fun updateDialog(change: (CustomMetricDialogState) -> CustomMetricDialogState) =
        local.update { ui -> ui.copy(customDialog = ui.customDialog?.let(change)) }

    fun onCustomSave() {
        val dialog = local.value.customDialog ?: return
        if (dialog.saving) return
        updateDialog { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val created = repository.addCustomMetric(dialog.name, dialog.unit)
                local.update { it.copy(customDialog = null) }
                onMetric(created.key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: InvalidCustomMetricException) {
                updateDialog { it.copy(saving = false, problem = e.problem) }
            } catch (e: Exception) {
                updateDialog { it.copy(saving = false, failed = true) }
            }
        }
    }
}
