package com.chrisgoac.gymlog.train

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.routine.Routine
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.workout.ExerciseHistory
import com.chrisgoac.gymlog.domain.workout.FinishResult
import com.chrisgoac.gymlog.domain.workout.FinishWorkoutUseCase
import com.chrisgoac.gymlog.domain.workout.WorkoutSummary
import com.chrisgoac.gymlog.domain.workout.FieldParse
import com.chrisgoac.gymlog.domain.workout.HistorySet
import com.chrisgoac.gymlog.domain.workout.Progression
import com.chrisgoac.gymlog.domain.workout.ProgressionSettings
import com.chrisgoac.gymlog.domain.workout.RepTarget
import com.chrisgoac.gymlog.domain.workout.SetChange
import com.chrisgoac.gymlog.domain.workout.SetFieldError
import com.chrisgoac.gymlog.domain.workout.SetValidation
import com.chrisgoac.gymlog.domain.workout.Suggestion
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutLimits
import com.chrisgoac.gymlog.domain.workout.WorkoutLogRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import com.chrisgoac.gymlog.domain.timer.RestTimerCompletion
import com.chrisgoac.gymlog.domain.timer.RestTimerController
import com.chrisgoac.gymlog.domain.timer.RestTimerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class WorkoutLogStatus { Loading, Ready, Missing, Failed }

/** Progress of finishing the workout. [Finished] carries the summary shown on the closing screen. */
sealed interface FinishUiState {
    data object Idle : FinishUiState
    data object Saving : FinishUiState

    /** No repetition logged: ask "¿Terminar sin series?" before finishing anyway. */
    data object ConfirmEmpty : FinishUiState

    /** Nothing was finished (typed values could not be saved, or the workout vanished); retry is safe. */
    data object Failed : FinishUiState
    data class Finished(val summary: WorkoutSummary) : FinishUiState
}

/** Routine target for one exercise of the workout. */
data class ExerciseTarget(val sets: Int?, val repsMin: Int?, val repsMax: Int?, val restSeconds: Int?)

/**
 * One set row. [weightText]/[repsText] are what the field shows: the typed draft while there is
 * one, otherwise the stored value. Errors are for the typed draft (nothing invalid is stored).
 */
data class SetRowUi(
    val id: String,
    val number: Int,
    val weightText: String,
    val repsText: String,
    val warmup: Boolean,
    val completed: Boolean,
    val weightError: SetFieldError? = null,
    val repsError: SetFieldError? = null,
    /** The set in the same position last time, if any. */
    val previous: HistorySet? = null,
)

data class ExerciseLogUi(
    val id: String,
    val exerciseId: String,
    /** Null when the exercise no longer exists. */
    val name: String?,
    val target: ExerciseTarget?,
    /** Last set of the previous session of this exercise, and that session's date. */
    val previous: HistorySet?,
    val previousDate: Long?,
    val suggestion: Suggestion?,
    /** Short progression badge ("▲ +2.5 kg"), or null for none. */
    val suggestionBadge: String?,
    val sets: List<SetRowUi>,
    val isFirst: Boolean,
    val isLast: Boolean,
)

data class WorkoutLogUiState(
    val status: WorkoutLogStatus = WorkoutLogStatus.Loading,
    val startedAt: Long? = null,
    val notes: String = "",
    val notesTooLong: Boolean = false,
    val exercises: List<ExerciseLogUi> = emptyList(),
    val pickerOpen: Boolean = false,
    val pickerQuery: String = "",
    /** A local write failed (storage): shown until dismissed; the typed values are kept. */
    val writeFailed: Boolean = false,
)

/**
 * Logging screen of one workout. Typed weight/reps stay as text drafts (saved in [savedState], so
 * they survive process death) and reach Room only once they parse: each valid field edit is
 * committed ~[debounceMillis] after the last keystroke, while completing or adding a set commits
 * at once. Every committed change is one repository call (one local mutation).
 *
 * Writes run in a scope that outlives the ViewModel and are serialized, so [flushPending] (on
 * leave) and [onCleared] still persist what was typed. [onSetCompleted] receives the routine's rest
 * seconds for the completed set (null = no target: no timer starts, as in the PWA); the [restTimer]
 * is started with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModel(
    private val sessionId: String,
    private val workouts: WorkoutRepository,
    private val log: WorkoutLogRepository,
    exercises: ExerciseRepository,
    private val routines: RoutineRepository,
    private val savedState: SavedStateHandle,
    private val onSetCompleted: (restSeconds: Int?) -> Unit = {},
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val restTimer: RestTimerController? = null,
    private val finishWorkout: FinishWorkoutUseCase? = null,
) : ViewModel() {
    private val local = MutableStateFlow(restore())

    private val _finish = MutableStateFlow<FinishUiState>(FinishUiState.Idle)
    val finishState: StateFlow<FinishUiState> = _finish

    /** True when the finish action exists (a use case was provided). */
    val canFinish: Boolean get() = finishWorkout != null

    /**
     * Finishes the workout. Everything typed is committed FIRST; if any of it could not be stored
     * the workout is not finished, so a typed set is never lost. A workout without a repetition asks
     * for confirmation ([FinishUiState.ConfirmEmpty]); repeated taps are ignored.
     */
    fun finish() = runFinish(confirmEmpty = false)

    /** The user confirmed "Terminar sin series". */
    fun confirmFinishEmpty() = runFinish(confirmEmpty = true)

    /** Back to logging after a confirmation prompt or a failure. */
    fun cancelFinish() {
        _finish.update { if (it is FinishUiState.ConfirmEmpty || it is FinishUiState.Failed) FinishUiState.Idle else it }
    }

    private fun runFinish(confirmEmpty: Boolean) {
        val useCase = finishWorkout ?: return
        val current = _finish.value
        if (current is FinishUiState.Saving || current is FinishUiState.Finished) return
        if (!notesValid(local.value.notes.orEmpty())) return
        _finish.value = FinishUiState.Saving
        writeScope.launch {
            _finish.value = try {
                flushAll()
                if (hasUnsavedValidInput()) {
                    FinishUiState.Failed
                } else {
                    when (val result = useCase(sessionId, local.value.notes, confirmEmpty)) {
                        is FinishResult.Finished -> FinishUiState.Finished(result.summary)
                        FinishResult.NeedsConfirmation -> FinishUiState.ConfirmEmpty
                        FinishResult.NotFound -> FinishUiState.Failed
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                update { it.copy(writeFailed = true) }
                FinishUiState.Failed
            }
        }
    }

    private fun hasUnsavedValidInput(): Boolean {
        val l = local.value
        return l.drafts.values.any { it.hasValidField() } || (l.notesDirty && l.notes != null && notesValid(l.notes))
    }

    /** The account's rest timer when it belongs to this workout (restored from storage at creation). */
    val timer: StateFlow<RestTimerState?> = restTimer?.state ?: MutableStateFlow(null)

    /** Exactly-once end-of-rest events, for the haptic and the spoken announcement. */
    val timerCompletions: Flow<RestTimerCompletion> = restTimer?.completions ?: emptyFlow()

    fun nowMillis(): Long = restTimer?.now() ?: System.currentTimeMillis()

    /** Evaluates the end of the run (UI tick or app resume); a no-op until it is due. */
    fun onTimerTick() { restTimer?.let { writeScope.launch { it.tick() } } }
    fun pauseRest() { restTimer?.let { writeScope.launch { it.pause() } } }
    fun resumeRest() { restTimer?.let { writeScope.launch { it.resume() } } }
    fun extendRest(seconds: Int) { restTimer?.let { writeScope.launch { it.extend(seconds) } } }

    /** Skip / dismiss. */
    fun resetRest() { restTimer?.let { writeScope.launch { it.reset() } } }

    /** Not tied to viewModelScope: a commit started on leave must finish after the ViewModel is cleared. */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val writeLock = Mutex()
    private val timers = mutableMapOf<String, Job>()

    init {
        restTimer?.let { writeScope.launch { it.restore(sessionId) } }
    }

    private val attempt = MutableStateFlow(0)

    /**
     * ONE Room subscription to the workout per screen, shared by the state and its derived reads.
     * Failures are carried as values (a failing upstream must not escape into viewModelScope) and
     * tagged with the attempt, so after [retry] a stale failure is never replayed as current.
     */
    private val sharedWorkout: Flow<Pair<Int, Result<WorkoutAggregate?>>> = attempt
        .flatMapLatest { n ->
            workouts.observeWorkout(sessionId)
                .map { n to Result.success(it) }
                .catch { emit(n to Result.failure(it)) }
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

    /** Everything read from the repositories for the current attempt. */
    private fun snapshot(): Flow<Snapshot> {
        val current = attempt.value
        val workout = sharedWorkout.filter { it.first == current }.map { it.second.getOrThrow() }
        return combine(
            workout,
            workout.map { w -> w?.let { HistoryKey(it.exercises.map { e -> e.exerciseId }.toSet(), it.session.gymId) } }
                .distinctUntilChanged()
                .flatMapLatest { key -> if (key == null) flowOf(emptyMap()) else log.observeExerciseHistory(key.exerciseIds, sessionId, key.gymId) },
            workout.map { it?.session?.routineId }.distinctUntilChanged()
                .flatMapLatest { id -> if (id == null) flowOf(null) else routines.observeRoutine(id) },
            exerciseCatalog.observeExercises().map { all -> all.associateBy { it.id } },
            log.observeProgressionSettings(),
        ) { w, history, routine, catalog, settings -> Snapshot(w, history, routine, catalog, settings) }
    }

    private val exerciseCatalog = exercises

    val state: StateFlow<WorkoutLogUiState> = attempt
        .flatMapLatest {
            combine(snapshot(), local, ::build)
                .onStart { emit(WorkoutLogUiState()) }
                .catch { emit(WorkoutLogUiState(WorkoutLogStatus.Failed)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), WorkoutLogUiState())

    /** Exercises matching the picker query; empty while the picker is closed. */
    val pickerResults: StateFlow<List<Exercise>> = local
        .map { if (it.pickerOpen) it.pickerQuery else null }
        .distinctUntilChanged()
        .flatMapLatest { query -> if (query == null) flowOf(emptyList()) else exercises.observeExercises(ExerciseFilter(query = query)) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Restored drafts were typed but maybe never committed: commit the pending valid ones again.
        local.value.drafts.filterValues { it.hasValidField() }.keys.forEach(::schedule)
        if (local.value.notesDirty) schedule(NOTES_KEY)
    }

    /** After [WorkoutLogStatus.Failed]: subscribe to the stored data again. */
    fun retry() = attempt.update { it + 1 }

    // ---- Set fields ----

    fun onWeight(setId: String, text: String) = editDraft(setId) { it.copy(weight = text, weightDirty = true) }

    fun onReps(setId: String, text: String) = editDraft(setId) { it.copy(reps = text, repsDirty = true) }

    private fun editDraft(setId: String, transform: (SetDraft) -> SetDraft) {
        val next = update { l ->
            l.copy(drafts = l.drafts + (setId to transform(l.drafts[setId] ?: SetDraft())), completionErrors = l.completionErrors - setId)
        }
        val draft = next.drafts.getValue(setId)
        // Only something valid is worth waiting for; an invalid latest text cancels the pending write.
        if (draft.hasValidField()) schedule(setId) else cancelTimer(setId)
    }

    fun setWarmup(setId: String, warmup: Boolean) = launchWrite { log.updateSet(setId, SetChange(warmup = warmup)) }

    /**
     * Commits the set now and marks it completed. Repeated taps are ignored; the hook fires once.
     * Invalid fields or zero reps leave it open with the error shown.
     */
    fun completeSet(setId: String) {
        val current = local.value
        if (setId in current.completed) return
        val draft = current.drafts[setId]
        if (draft != null && draft.hasInvalidField()) return
        update { it.copy(completed = it.completed + setId) }
        cancelTimer(setId)
        writeScope.launch {
            val stored = currentSet(setId)
            val reps = draft?.pendingReps() ?: stored?.reps
            val error = if (stored == null) null else reps?.let(SetValidation::completionError)
            if (stored == null || error != null) {
                update { it.copy(completed = it.completed - setId, completionErrors = if (error != null) it.completionErrors + (setId to error) else it.completionErrors) }
                // Whatever valid was typed is still stored.
                commitSet(setId)
                return@launch
            }
            if (!commitSet(setId)) {
                update { it.copy(completed = it.completed - setId) }
                return@launch
            }
            // The committed draft is the stored value now; a newer one typed meanwhile is kept.
            update { l -> if (l.drafts[setId] == draft) l.copy(drafts = l.drafts - setId) else l }
            val rest = restSecondsFor(setId)
            restTimer?.start(sessionId, rest)
            onSetCompleted(rest)
        }
    }

    /** Takes the completed mark off (nothing is written). */
    fun reopenSet(setId: String) {
        update { it.copy(completed = it.completed - setId) }
    }

    /** Appends a set copying the last one (pending edits first), or the suggestion for the first set. */
    fun addSet(loggedExerciseId: String) {
        if (loggedExerciseId in local.value.addingSets) return
        update { it.copy(addingSets = it.addingSets + loggedExerciseId) }
        writeScope.launch {
            try {
                val before = freshWorkout()?.exercises?.firstOrNull { it.id == loggedExerciseId } ?: return@launch
                before.sets.forEach { flushSet(it.id) }
                // Read again after the flush: the stored values are the ones just committed (or newer).
                val data = snapshot().first()
                val exercise = data.workout?.exercises?.firstOrNull { it.id == loggedExerciseId } ?: return@launch
                val last = exercise.sets.lastOrNull()
                val (weight, reps) = if (last != null) {
                    last.weight to last.reps
                } else {
                    suggestionFor(data, exercise).first
                        ?.takeIf { SetValidation.isStorable(it.weight, it.reps) }
                        ?.let { it.weight to it.reps }
                        ?: (0.0 to 0)
                }
                write { log.addSet(loggedExerciseId, weight, reps) }
            } finally {
                update { it.copy(addingSets = it.addingSets - loggedExerciseId) }
            }
        }
    }

    /** Pending edits of the set are dropped only once the removal is stored; on failure they are kept and retried. */
    fun removeSet(setId: String) {
        cancelTimer(setId)
        writeScope.launch {
            if (write { log.removeSet(setId) }) forget(setOf(setId)) else reschedule(setOf(setId))
        }
    }

    fun moveSet(setId: String, delta: Int) = launchWrite { log.moveSet(setId, delta) }

    // ---- Exercises ----

    fun openPicker() {
        update { it.copy(pickerOpen = true, pickerQuery = "") }
    }

    fun closePicker() {
        update { it.copy(pickerOpen = false, pickerQuery = "") }
    }

    fun onPickerQuery(value: String) {
        update { it.copy(pickerQuery = value) }
    }

    /** Adds [exercise] at the end and closes the picker; a second tap while it is being added is ignored. */
    fun addExercise(exercise: Exercise) {
        if (local.value.addingExercise) return
        update { it.copy(addingExercise = true, pickerOpen = false, pickerQuery = "") }
        writeScope.launch {
            try {
                write { log.addExercise(sessionId, exercise.id) }
            } finally {
                update { it.copy(addingExercise = false) }
            }
        }
    }

    fun removeExercise(loggedExerciseId: String) {
        writeScope.launch {
            val setIds = freshWorkout()?.exercises?.firstOrNull { it.id == loggedExerciseId }?.sets?.map { it.id }.orEmpty().toSet()
            setIds.forEach(::cancelTimer)
            if (write { log.removeExercise(loggedExerciseId) }) forget(setIds) else reschedule(setIds)
        }
    }

    fun moveExercise(loggedExerciseId: String, delta: Int) = launchWrite { log.moveExercise(loggedExerciseId, delta) }

    // ---- Notes ----

    fun onNotes(text: String) {
        update { it.copy(notes = text, notesDirty = true) }
        if (notesValid(text)) schedule(NOTES_KEY) else cancelTimer(NOTES_KEY)
    }

    fun dismissWriteError() {
        update { it.copy(writeFailed = false) }
    }

    // ---- Flushing ----

    /** Commits every pending edit now (call before leaving the screen or finishing). */
    fun flushPending(): Job = writeScope.launch { flushAll() }

    override fun onCleared() {
        writeScope.launch { flushAll() }
    }

    private suspend fun flushAll() {
        local.value.drafts.keys.forEach { flushSet(it) }
        cancelTimer(NOTES_KEY)
        commitNotes()
    }

    private suspend fun flushSet(setId: String) {
        cancelTimer(setId)
        commitSet(setId)
    }

    private fun schedule(key: String) {
        cancelTimer(key)
        timers[key] = writeScope.launch {
            delay(debounceMillis)
            timers.remove(key)
            if (key == NOTES_KEY) commitNotes() else commitSet(key)
        }
    }

    private fun cancelTimer(key: String) {
        timers.remove(key)?.cancel()
    }

    /**
     * Writes the draft's pending valid fields (field by field). A field stops being pending once
     * written (unless retyped meanwhile), so later flushes never overwrite a newer stored value.
     * False if a write failed.
     */
    private suspend fun commitSet(setId: String): Boolean {
        val draft = local.value.drafts[setId] ?: return true
        val change = SetChange(weight = draft.pendingWeight(), reps = draft.pendingReps())
        if (change == SetChange()) return true
        val ok = write { log.updateSet(setId, change) }
        if (ok) {
            update { l ->
                val current = l.drafts[setId] ?: return@update l
                val settled = current.copy(
                    weightDirty = current.weightDirty && !(change.weight != null && current.weight == draft.weight),
                    repsDirty = current.repsDirty && !(change.reps != null && current.reps == draft.reps),
                )
                l.copy(drafts = l.drafts + (setId to settled))
            }
        }
        return ok
    }

    private fun reschedule(setIds: Set<String>) {
        setIds.filter { local.value.drafts[it]?.hasValidField() == true }.forEach(::schedule)
    }

    private suspend fun commitNotes(): Boolean {
        val current = local.value
        val notes = current.notes ?: return true
        if (!current.notesDirty || !notesValid(notes)) return true
        val ok = write { log.updateNotes(sessionId, notes) }
        if (ok) update { if (it.notes == notes) it.copy(notesDirty = false) else it }
        return ok
    }

    private fun launchWrite(block: suspend () -> Unit) {
        writeScope.launch { write(block) }
    }

    /** Serialized local write; a failure is shown, never thrown. */
    private suspend fun write(block: suspend () -> Unit): Boolean = writeLock.withLock {
        try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            update { it.copy(writeFailed = true) }
            false
        }
    }

    /** Read straight from the repository: also valid after the ViewModel (and its shared flows) is cleared. */
    private suspend fun freshWorkout(): WorkoutAggregate? = workouts.observeWorkout(sessionId).first()

    private suspend fun currentSet(setId: String): WorkoutSet? =
        freshWorkout()?.exercises?.flatMap { it.sets }?.firstOrNull { it.id == setId }

    private suspend fun restSecondsFor(setId: String): Int? {
        val current = freshWorkout() ?: return null
        val exerciseId = current.exercises.firstOrNull { e -> e.sets.any { it.id == setId } }?.exerciseId ?: return null
        val routineId = current.session.routineId ?: return null
        return routines.observeRoutine(routineId).first()?.exercises?.firstOrNull { it.exerciseId == exerciseId }?.restSeconds
    }

    private fun forget(setIds: Set<String>) {
        update { it.copy(drafts = it.drafts - setIds, completed = it.completed - setIds, completionErrors = it.completionErrors - setIds) }
    }

    // ---- State ----

    private fun build(data: Snapshot, l: Local): WorkoutLogUiState {
        val aggregate = data.workout
            ?: return WorkoutLogUiState(WorkoutLogStatus.Missing, pickerOpen = l.pickerOpen, pickerQuery = l.pickerQuery, writeFailed = l.writeFailed)
        val routine = data.routine?.takeIf { aggregate.session.routineId == it.id }
        val stored = aggregate.session.notes.orEmpty()
        // A settled draft only shows while it still matches what is stored (a synced change wins).
        val notes = l.notes?.takeIf { l.notesDirty || it.trim() == stored } ?: stored
        return WorkoutLogUiState(
            status = WorkoutLogStatus.Ready,
            startedAt = aggregate.session.startedAt,
            notes = notes,
            notesTooLong = !notesValid(notes),
            exercises = aggregate.exercises.mapIndexed { index, exercise ->
                val target = routine?.exercises?.firstOrNull { it.exerciseId == exercise.exerciseId }
                    ?.let { ExerciseTarget(it.sets, it.repsMin, it.repsMax, it.restSeconds) }
                val history = data.history[exercise.exerciseId]
                val definition = data.exercises[exercise.exerciseId]
                val (suggestion, badge) = suggestionFor(data, exercise)
                ExerciseLogUi(
                    id = exercise.id,
                    exerciseId = exercise.exerciseId,
                    name = exercise.exerciseName ?: definition?.name,
                    target = target,
                    previous = history?.lastSet,
                    previousDate = history?.date,
                    suggestion = suggestion,
                    suggestionBadge = badge,
                    sets = exercise.sets.mapIndexed { position, set -> row(set, position, l, history) },
                    isFirst = index == 0,
                    isLast = index == aggregate.exercises.lastIndex,
                )
            },
            pickerOpen = l.pickerOpen,
            pickerQuery = l.pickerQuery,
            writeFailed = l.writeFailed,
        )
    }

    private fun row(set: WorkoutSet, position: Int, l: Local, history: ExerciseHistory?): SetRowUi {
        val draft = l.drafts[set.id]
        // The typed text stays while pending, or while it still means the stored value ("62," for 62):
        // the cursor does not jump, yet a synced change replaces a settled draft.
        val weightText = draft?.weight?.takeIf { draft.weightDirty || draft.validWeight() == set.weight }
        val repsText = draft?.reps?.takeIf { draft.repsDirty || draft.validReps() == set.reps }
        return SetRowUi(
            id = set.id,
            number = position + 1,
            weightText = weightText ?: SetValidation.formatWeight(set.weight),
            repsText = repsText ?: set.reps.toString(),
            warmup = set.warmup,
            completed = set.id in l.completed,
            weightError = draft?.takeIf { it.weightDirty }?.weight?.let { (SetValidation.parseWeight(it) as? FieldParse.Invalid)?.error },
            repsError = draft?.takeIf { it.repsDirty }?.reps?.let { (SetValidation.parseReps(it) as? FieldParse.Invalid)?.error }
                ?: l.completionErrors[set.id],
            previous = history?.sets?.getOrNull(position),
        )
    }

    /** PWA LoggedExerciseCard: the routine target only counts in a routine workout. */
    private fun suggestionFor(data: Snapshot, logged: WorkoutExercise): Pair<Suggestion?, String?> {
        val routine = data.routine?.takeIf { data.workout?.session?.routineId == it.id }
        val target = routine?.exercises?.firstOrNull { it.exerciseId == logged.exerciseId }
            ?.let { ExerciseTarget(it.sets, it.repsMin, it.repsMax, it.restSeconds) }
        return suggestionFor(data.exercises[logged.exerciseId], data.history[logged.exerciseId], target, routine != null, data.settings)
    }

    private fun suggestionFor(
        exercise: Exercise?,
        history: ExerciseHistory?,
        target: ExerciseTarget?,
        fromRoutine: Boolean,
        settings: ProgressionSettings,
    ): Pair<Suggestion?, String?> {
        if (exercise == null) return null to null
        val working = history?.workingSets
        val step = Progression.inferStep(working.orEmpty().map { it.weight }, exercise.equipment, settings.increments, exercise.incrementKg)
        val suggestion = Progression.suggest(
            mode = settings.mode,
            last = working,
            target = if (fromRoutine) target?.let { RepTarget(repsMax = it.repsMax, repsMin = it.repsMin) } else null,
            step = step,
            bodyweight = exercise.equipment == Equipment.PESO_CORPORAL,
        )
        return suggestion to Progression.describe(suggestion, step)
    }

    private fun notesValid(text: String) = text.trim().length <= WorkoutLimits.NOTES_MAX

    private fun update(transform: (Local) -> Local): Local = local.updateAndGet(transform).also(::persist)

    // ---- Saved state (process death) ----

    private fun persist(l: Local) {
        val ids = ArrayList(l.drafts.keys)
        savedState[KEY_DRAFT_IDS] = ids
        savedState[KEY_DRAFT_WEIGHTS] = ArrayList(ids.map { l.drafts.getValue(it).weight ?: ABSENT })
        savedState[KEY_DRAFT_REPS] = ArrayList(ids.map { l.drafts.getValue(it).reps ?: ABSENT })
        savedState[KEY_DRAFT_DIRTY] = ArrayList(ids.map { l.drafts.getValue(it).let { d -> "${if (d.weightDirty) 1 else 0}${if (d.repsDirty) 1 else 0}" } })
        savedState[KEY_NOTES_DIRTY] = l.notesDirty
        savedState[KEY_COMPLETED] = ArrayList(l.completed)
        savedState[KEY_NOTES] = l.notes
        savedState[KEY_PICKER_OPEN] = l.pickerOpen
        savedState[KEY_PICKER_QUERY] = l.pickerQuery
    }

    private fun restore(): Local {
        val ids = savedState.get<ArrayList<String>>(KEY_DRAFT_IDS).orEmpty()
        val weights = savedState.get<ArrayList<String>>(KEY_DRAFT_WEIGHTS).orEmpty()
        val reps = savedState.get<ArrayList<String>>(KEY_DRAFT_REPS).orEmpty()
        val dirty = savedState.get<ArrayList<String>>(KEY_DRAFT_DIRTY).orEmpty()
        val drafts = if (weights.size == ids.size && reps.size == ids.size && dirty.size == ids.size) {
            ids.indices.associate { i ->
                ids[i] to SetDraft(
                    weight = weights[i].takeIf { it != ABSENT },
                    reps = reps[i].takeIf { it != ABSENT },
                    weightDirty = dirty[i].getOrNull(0) == '1',
                    repsDirty = dirty[i].getOrNull(1) == '1',
                )
            }
        } else {
            emptyMap()
        }
        return Local(
            drafts = drafts,
            completed = savedState.get<ArrayList<String>>(KEY_COMPLETED).orEmpty().toSet(),
            notes = savedState.get<String>(KEY_NOTES),
            notesDirty = savedState.get<Boolean>(KEY_NOTES_DIRTY) ?: false,
            pickerOpen = savedState.get<Boolean>(KEY_PICKER_OPEN) ?: false,
            pickerQuery = savedState.get<String>(KEY_PICKER_QUERY).orEmpty(),
        )
    }

    /**
     * Typed text per field: null = never edited. A dirty field has not been written since it was
     * typed; a settled one was written and only keeps the text so the cursor does not jump.
     */
    private data class SetDraft(
        val weight: String? = null,
        val reps: String? = null,
        val weightDirty: Boolean = false,
        val repsDirty: Boolean = false,
    ) {
        fun validWeight(): Double? = weight?.let { (SetValidation.parseWeight(it) as? FieldParse.Valid)?.value }
        fun validReps(): Int? = reps?.let { (SetValidation.parseReps(it) as? FieldParse.Valid)?.value }
        fun pendingWeight(): Double? = if (weightDirty) validWeight() else null
        fun pendingReps(): Int? = if (repsDirty) validReps() else null
        fun hasValidField() = pendingWeight() != null || pendingReps() != null
        fun hasInvalidField() = (weightDirty && validWeight() == null) || (repsDirty && validReps() == null)
    }

    private data class Local(
        val drafts: Map<String, SetDraft> = emptyMap(),
        val completed: Set<String> = emptySet(),
        val completionErrors: Map<String, SetFieldError> = emptyMap(),
        /** Typed notes; null = untouched (the stored notes show). */
        val notes: String? = null,
        /** [notes] typed and not written yet. */
        val notesDirty: Boolean = false,
        val pickerOpen: Boolean = false,
        val pickerQuery: String = "",
        val writeFailed: Boolean = false,
        val addingSets: Set<String> = emptySet(),
        val addingExercise: Boolean = false,
    )

    private data class HistoryKey(val exerciseIds: Set<String>, val gymId: String?)

    private data class Snapshot(
        val workout: WorkoutAggregate?,
        val history: Map<String, ExerciseHistory>,
        val routine: Routine?,
        val exercises: Map<String, Exercise>,
        val settings: ProgressionSettings,
    )

    private companion object {
        const val DEBOUNCE_MILLIS = 400L
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val NOTES_KEY = "\u0000notes"
        /** Marks "not edited" in the saved lists (a typed field never contains NUL). */
        const val ABSENT = "\u0000"
        const val KEY_DRAFT_IDS = "workout.draftIds"
        const val KEY_DRAFT_WEIGHTS = "workout.draftWeights"
        const val KEY_DRAFT_REPS = "workout.draftReps"
        const val KEY_COMPLETED = "workout.completed"
        const val KEY_NOTES = "workout.notes"
        const val KEY_DRAFT_DIRTY = "workout.draftDirty"
        const val KEY_NOTES_DIRTY = "workout.notesDirty"
        const val KEY_PICKER_OPEN = "workout.pickerOpen"
        const val KEY_PICKER_QUERY = "workout.pickerQuery"
    }
}
