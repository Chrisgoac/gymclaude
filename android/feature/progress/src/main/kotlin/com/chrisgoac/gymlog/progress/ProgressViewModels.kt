package com.chrisgoac.gymlog.progress

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.stats.BalanceRow
import com.chrisgoac.gymlog.domain.stats.ExerciseRecords
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.ProgressPoint
import com.chrisgoac.gymlog.domain.stats.ProgressionSuggestion
import com.chrisgoac.gymlog.domain.stats.Stall
import com.chrisgoac.gymlog.domain.stats.StatsFilter
import com.chrisgoac.gymlog.domain.stats.StatsRepository
import com.chrisgoac.gymlog.domain.stats.Streak
import com.chrisgoac.gymlog.domain.stats.VolumeOverview
import com.chrisgoac.gymlog.domain.stats.WeeklyInsight
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import java.time.ZoneId

private const val STOP_TIMEOUT_MILLIS = 5_000L
private const val KEY_PERIOD = "period"
private const val KEY_GYM = "gym"
private const val KEY_METRIC = "metric"

enum class ProgressStatus { Loading, Ready, Failed }

/** Clock and zone the data was computed with (server-corrected clock, device zone), for "hace N días" labels. */
data class ProgressClock(val nowMillis: Long, val zone: ZoneId)

/** What the Progress screen shows for the selected period and gym. */
data class ProgressData(
    val weekly: WeeklyInsight,
    val streak: Streak,
    val volume: VolumeOverview,
    val balance: List<BalanceRow>,
    val stalls: List<Stall>,
    val suggestions: List<ProgressionSuggestion>,
) {
    /** Nothing trained anywhere in view: the screen explains how to start instead of showing zeroed cards. */
    val isEmpty: Boolean
        get() = volume.summary.sessions == 0 && weekly.summary.sessions == 0 && weekly.records.isEmpty() &&
            stalls.isEmpty() && streak.days == 0 && balance.none { it.daysSinceTrained != null || it.volume > 0.0 }
}

data class ProgressUiState(
    val status: ProgressStatus = ProgressStatus.Loading,
    val period: Period = Period.FOUR_WEEKS,
    val gym: GymFilter = GymFilter.All,
    val gyms: List<Gym> = emptyList(),
    val data: ProgressData? = null,
    val clock: ProgressClock? = null,
    /** Pro entitlement: the intelligent report action is enabled only when true. */
    val isPro: Boolean = false,
    val exerciseQuery: String = "",
    val exerciseResults: List<Exercise> = emptyList(),
)

/**
 * Emits the first value at once and then at most one value per [intervalMillis] (always the latest):
 * a burst of invalidations (a sync pull writing hundreds of rows) recomputes once, not per row.
 */
internal fun <T> Flow<T>.rateLimited(intervalMillis: Long): Flow<T> =
    conflate().transform { value ->
        emit(value)
        delay(intervalMillis)
    }

/** Restores/persists the filter in [SavedStateHandle] (device-only; DataStore settings arrive with Plan 4 Task 6). */
private fun SavedStateHandle.period(): Period = get<String>(KEY_PERIOD)?.let(Period::fromWire) ?: Period.FOUR_WEEKS
private fun SavedStateHandle.gym(): GymFilter = GymFilter.fromWire(get<String>(KEY_GYM))

/**
 * Progress screen state. Every derived flow is re-subscribed when the period/gym changes or the local
 * day rolls over ([ticks]), recomputes are rate limited ([RECOMPUTE_INTERVAL_MILLIS]) so a burst of
 * writes costs one recompute, and collection stops five seconds after the screen is gone.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ProgressViewModel(
    private val stats: StatsRepository,
    workouts: WorkoutRepository,
    private val exercises: ExerciseRepository,
    entitlements: EntitlementReader,
    private val savedState: SavedStateHandle,
    private val ticks: Flow<Unit>,
    private val clock: () -> ProgressClock,
    private val recomputeIntervalMillis: Long = RECOMPUTE_INTERVAL_MILLIS,
) : ViewModel() {
    private val filter = MutableStateFlow(StatsFilter(savedState.period(), savedState.gym()))
    private val query = MutableStateFlow("")
    private val attempt = MutableStateFlow(0)

    private data class Content(val status: ProgressStatus, val data: ProgressData?, val clock: ProgressClock?)

    private val content: Flow<Content> = combine(filter, ticks, attempt) { f, _, _ -> f }
        .flatMapLatest { f ->
            dataFlow(f)
                .rateLimited(recomputeIntervalMillis)
                .map { Content(ProgressStatus.Ready, it, clock()) }
                .onStart { emit(Content(ProgressStatus.Loading, null, null)) }
                .catch { emit(Content(ProgressStatus.Failed, null, null)) }
        }

    private val results: Flow<List<Exercise>> = query
        .debounce { if (it.isBlank()) 0L else QUERY_DEBOUNCE_MILLIS }
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else exercises.observeExercises(ExerciseFilter(query = q)).map { it.take(MAX_EXERCISE_RESULTS) }.catch { emit(emptyList()) }
        }

    private val sideInputs: Flow<Triple<List<Gym>, Boolean, List<Exercise>>> = combine(
        workouts.observeGyms().onEach(::dropMissingGym).catch { emit(emptyList()) },
        entitlements.observeIsPro().catch { emit(false) },
        results,
    ) { gyms, pro, found -> Triple(gyms, pro, found) }

    val state: StateFlow<ProgressUiState> = combine(content, sideInputs, filter, query) { c, side, f, q ->
        ProgressUiState(c.status, f.period, f.gym, side.first, c.data, c.clock, side.second, q, side.third)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        ProgressUiState(period = filter.value.period, gym = filter.value.gym),
    )

    private fun dataFlow(f: StatsFilter): Flow<ProgressData> = combine(
        stats.observeWeeklyInsight(f.gym),
        stats.observeStreak(),
        stats.observeVolume(f),
        stats.observeMuscleBalance(f),
        stats.observeStalls(f.gym),
        stats.observeProgressionSuggestions(f.gym),
    ) { values -> @Suppress("UNCHECKED_CAST") ProgressData(
        values[0] as WeeklyInsight, values[1] as Streak, values[2] as VolumeOverview,
        values[3] as List<BalanceRow>, values[4] as List<Stall>, values[5] as List<ProgressionSuggestion>,
    ) }

    fun onPeriod(period: Period) {
        savedState[KEY_PERIOD] = period.wire
        filter.update { it.copy(period = period) }
    }

    fun onGym(gym: GymFilter) {
        savedState[KEY_GYM] = gym.gymId ?: GymFilter.ALL_WIRE
        filter.update { it.copy(gym = gym) }
    }

    /** A saved/selected gym that is no longer offered (deleted or archived) falls back to every gym. */
    private fun dropMissingGym(gyms: List<Gym>) {
        val selected = filter.value.gym as? GymFilter.Gym ?: return
        if (gyms.none { it.id == selected.id }) onGym(GymFilter.All)
    }

    fun onExerciseQuery(text: String) {
        query.value = text
    }

    fun retry() = attempt.update { it + 1 }

    companion object {
        const val RECOMPUTE_INTERVAL_MILLIS = 150L
        const val QUERY_DEBOUNCE_MILLIS = 250L
        const val MAX_EXERCISE_RESULTS = 8
    }
}

// ---- Exercise drill-down ----

/** Which per-session value the exercise line shows (PWA `Metric`). */
enum class ProgressMetric { ONE_REP_MAX, MAX_WEIGHT, VOLUME }

data class ExerciseProgressUiState(
    val status: ProgressStatus = ProgressStatus.Loading,
    val exerciseName: String? = null,
    val period: Period = Period.THREE_MONTHS,
    val gym: GymFilter = GymFilter.All,
    val gyms: List<Gym> = emptyList(),
    val metric: ProgressMetric = ProgressMetric.ONE_REP_MAX,
    /** One point per training date, oldest first. */
    val series: List<ProgressPoint> = emptyList(),
    val records: ExerciseRecords? = null,
    val estimatedOneRepMax: Double? = null,
    val clock: ProgressClock? = null,
)

/** Series, records and estimated 1RM of one exercise; period, gym and metric are restored after process death. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseProgressViewModel(
    private val exerciseId: String,
    private val stats: StatsRepository,
    exercises: ExerciseRepository,
    workouts: WorkoutRepository,
    private val savedState: SavedStateHandle,
    private val ticks: Flow<Unit>,
    private val clock: () -> ProgressClock,
    initialFilter: StatsFilter,
    private val recomputeIntervalMillis: Long = ProgressViewModel.RECOMPUTE_INTERVAL_MILLIS,
) : ViewModel() {
    private val filter = MutableStateFlow(
        StatsFilter(
            savedState.get<String>(KEY_PERIOD)?.let(Period::fromWire) ?: initialFilter.period,
            savedState.get<String>(KEY_GYM)?.let { GymFilter.fromWire(it) } ?: initialFilter.gym,
        ),
    )
    private val metric = MutableStateFlow(savedState.get<String>(KEY_METRIC)?.let { name -> ProgressMetric.entries.firstOrNull { it.name == name } } ?: ProgressMetric.ONE_REP_MAX)
    private val attempt = MutableStateFlow(0)

    private data class Content(val status: ProgressStatus, val series: List<ProgressPoint>, val records: ExerciseRecords?, val oneRepMax: Double?, val clock: ProgressClock?)

    private val content: Flow<Content> = combine(filter, ticks, attempt) { f, _, _ -> f }
        .flatMapLatest { f ->
            combine(
                stats.observeExerciseSeries(exerciseId, f),
                stats.observeExerciseRecords(exerciseId, f.gym),
                stats.observeEstimatedOneRepMax(exerciseId, f.gym),
            ) { series, records, oneRepMax -> Triple(series, records, oneRepMax) }
                .rateLimited(recomputeIntervalMillis)
                .map { (series, records, oneRepMax) -> Content(ProgressStatus.Ready, series, records, oneRepMax, clock()) }
                .onStart { emit(Content(ProgressStatus.Loading, emptyList(), null, null, null)) }
                .catch { emit(Content(ProgressStatus.Failed, emptyList(), null, null, null)) }
        }

    private val name: Flow<String?> = exercises.observeExercise(exerciseId).map { it?.name }.catch { emit(null) }
    private val gyms: Flow<List<Gym>> = workouts.observeGyms().catch { emit(emptyList()) }

    val state: StateFlow<ExerciseProgressUiState> = combine(content, name, gyms, filter, metric) { c, n, g, f, m ->
        ExerciseProgressUiState(c.status, n, f.period, f.gym, g, m, c.series, c.records, c.oneRepMax, c.clock)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        ExerciseProgressUiState(period = filter.value.period, gym = filter.value.gym, metric = metric.value),
    )

    fun onPeriod(period: Period) {
        savedState[KEY_PERIOD] = period.wire
        filter.update { it.copy(period = period) }
    }

    fun onGym(gym: GymFilter) {
        savedState[KEY_GYM] = gym.gymId ?: GymFilter.ALL_WIRE
        filter.update { it.copy(gym = gym) }
    }

    fun onMetric(value: ProgressMetric) {
        savedState[KEY_METRIC] = value.name
        metric.value = value
    }

    fun retry() = attempt.update { it + 1 }
}
