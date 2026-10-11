package com.chrisgoac.gymlog.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.history.DraftError
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.history.LoggedExerciseDraft
import com.chrisgoac.gymlog.domain.history.LoggedSetDraft
import com.chrisgoac.gymlog.domain.history.SaveResult
import com.chrisgoac.gymlog.domain.history.SaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.WorkoutDraft
import com.chrisgoac.gymlog.domain.history.WorkoutDraftCodec
import com.chrisgoac.gymlog.domain.history.WorkoutDraftValidation
import com.chrisgoac.gymlog.domain.workout.FieldParse
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.HistoryRepository
import com.chrisgoac.gymlog.domain.workout.SetFieldError
import com.chrisgoac.gymlog.domain.workout.SetValidation
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutLimits
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.UUID

enum class EditorStatus { Loading, Ready, NotFound, Failed }

/** One problem of the summary shown before saving; rows are named by position for the user. */
sealed interface EditorIssue {
    data object DateUnreadable : EditorIssue
    data object DateInFuture : EditorIssue
    data object DateOutOfRange : EditorIssue
    data object GymUnavailable : EditorIssue
    data object NotesTooLong : EditorIssue
    data class UnknownExercise(val exerciseNumber: Int) : EditorIssue
    data class InvalidWeight(val exerciseName: String?, val setNumber: Int, val error: SetFieldError) : EditorIssue
    data class InvalidReps(val exerciseName: String?, val setNumber: Int, val error: SetFieldError) : EditorIssue
}

data class EditorSetUi(
    val id: String,
    val number: Int,
    val weightText: String,
    val repsText: String,
    val warmup: Boolean,
    val weightError: SetFieldError?,
    val repsError: SetFieldError?,
)

data class EditorExerciseUi(
    val id: String,
    /** Null when the exercise no longer exists. */
    val name: String?,
    val sets: List<EditorSetUi>,
    val isFirst: Boolean,
    val isLast: Boolean,
)

data class HistoryEditorUiState(
    val status: EditorStatus = EditorStatus.Loading,
    val dateText: String = "",
    val timeText: String = "",
    val dateUnreadable: Boolean = false,
    val gymId: String? = null,
    /** Live gyms to choose from, plus the current one if it is archived (by [currentGymName]). */
    val gyms: List<Gym> = emptyList(),
    val currentGymName: String? = null,
    val notes: String = "",
    val notesTooLong: Boolean = false,
    val exercises: List<EditorExerciseUi> = emptyList(),
    val dirty: Boolean = false,
    val pickerOpen: Boolean = false,
    val pickerQuery: String = "",
    val saving: Boolean = false,
    /** Every problem at once; shown after a save was refused. */
    val issues: List<EditorIssue> = emptyList(),
    val showIssues: Boolean = false,
    val confirmingExit: Boolean = false,
    /** The workout changed on another device since it was opened: only reloading is possible. */
    val conflict: Boolean = false,
    /** The workout was deleted meanwhile. */
    val gone: Boolean = false,
    val saveFailed: Boolean = false,
)

/**
 * Edits a past workout as a whole: date, gym, notes, exercises (add, remove, reorder) and sets
 * (add, remove, reorder, values). Everything lives in a [WorkoutDraft] kept in [savedState] (with
 * the original it was opened from), so rotation and process death keep the edit and a newer
 * synced version is still detected on save. Nothing touches storage until [save], which is ONE
 * mutation; leaving without saving ([discard]) writes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryEditorViewModel(
    private val sessionId: String,
    private val history: HistoryRepository,
    workouts: WorkoutRepository,
    exercises: ExerciseRepository,
    private val saveDraft: SaveWorkoutDraftUseCase,
    private val savedState: SavedStateHandle,
    /** Server-corrected clock, used for the "not in the future" check. */
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    /** Editable state; null until the original is known. */
    private data class Editing(
        val original: WorkoutAggregate,
        val originalGymName: String?,
        val draft: WorkoutDraft,
        val dateText: String,
        val timeText: String,
        val pickerOpen: Boolean = false,
        val pickerQuery: String = "",
        val showIssues: Boolean = false,
        val confirmingExit: Boolean = false,
        /** Reference problems found by the last save (they need storage to check). */
        val referenceErrors: List<DraftError> = emptyList(),
    )

    private data class Transient(
        val status: EditorStatus = EditorStatus.Loading,
        val saving: Boolean = false,
        val conflict: Boolean = false,
        val gone: Boolean = false,
        val saveFailed: Boolean = false,
    )

    private val editing = MutableStateFlow(restore())
    private val transient = MutableStateFlow(Transient(status = if (editing.value != null) EditorStatus.Ready else EditorStatus.Loading))
    private val exitFlag = MutableStateFlow(false)
    private val savedScopes = MutableStateFlow<Set<InvalidationScope>?>(null)

    /** True once the editor should close (saved, or changes discarded). */
    val exit: StateFlow<Boolean> = exitFlag

    /** What the last successful save made stale (for Phase 4 consumers); null until saved. */
    val lastInvalidatedScopes: StateFlow<Set<InvalidationScope>?> = savedScopes

    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

    val state: StateFlow<HistoryEditorUiState> = combine(
        editing,
        transient,
        workouts.observeGyms().catch { emit(emptyList()) },
    ) { e, t, gyms -> render(e, t, gyms) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, render(editing.value, transient.value, emptyList()))

    /** Search results for the add-exercise picker (only queried while it is open). */
    val pickerResults: StateFlow<List<Exercise>> = editing
        .map { it?.takeIf { e -> e.pickerOpen }?.pickerQuery }
        .distinctUntilChanged()
        .flatMapLatest { query -> if (query == null) flowOf(emptyList()) else exercises.observeExercises(ExerciseFilter(query = query)).catch { emit(emptyList()) } }
        .map { it.take(MAX_PICKER_RESULTS) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (editing.value == null) load()
    }

    private fun load() {
        transient.value = Transient(status = EditorStatus.Loading)
        viewModelScope.launch {
            try {
                val detail = history.observeDetail(sessionId).first()
                if (detail == null) {
                    transient.value = Transient(status = EditorStatus.NotFound)
                } else {
                    val start = Instant.ofEpochMilli(detail.workout.session.startedAt).atZone(zone)
                    set(
                        Editing(
                            original = detail.workout,
                            originalGymName = detail.gymName,
                            draft = WorkoutDraft.from(detail.workout),
                            dateText = start.format(dateFormat),
                            timeText = start.format(timeFormat),
                        ),
                    )
                    transient.value = Transient(status = EditorStatus.Ready)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                transient.value = Transient(status = EditorStatus.Failed)
            }
        }
    }

    fun retry() = load()

    /** After a conflict: drop the draft and start again from the stored version. */
    fun reload() {
        set(null)
        load()
    }

    // ---- Session fields ----

    fun onDate(text: String) = edit { it.copy(dateText = text) }

    fun onTime(text: String) = edit { it.copy(timeText = text) }

    fun onGym(gymId: String?) = editDraft { it.copy(gymId = gymId) }

    fun onNotes(text: String) = editDraft { it.copy(notes = text) }

    // ---- Exercises ----

    fun openPicker() = edit { it.copy(pickerOpen = true, pickerQuery = "") }

    fun closePicker() = edit { it.copy(pickerOpen = false, pickerQuery = "") }

    fun onPickerQuery(query: String) = edit { it.copy(pickerQuery = query) }

    /** Appends [exercise] (no sets yet) with a new stable id; the picker closes. */
    fun addExercise(exercise: Exercise) = edit {
        it.copy(
            draft = it.draft.copy(exercises = it.draft.exercises + LoggedExerciseDraft(newId(), exercise.id, exercise.name, emptyList())),
            pickerOpen = false,
            pickerQuery = "",
        )
    }

    fun removeExercise(id: String) = editDraft { it.removeExercise(id) }

    fun moveExercise(id: String, delta: Int) = editDraft { it.moveExercise(id, delta) }

    // ---- Sets ----

    /** New set copying the exercise's last one (blank for the first, so it must be filled in). */
    fun addSet(exerciseId: String) = editDraft { draft ->
        draft.withExercise(exerciseId) { exercise ->
            val last = exercise.sets.lastOrNull()
            exercise.copy(sets = exercise.sets + LoggedSetDraft(newId(), last?.weight.orEmpty(), last?.reps.orEmpty(), false))
        }
    }

    fun onWeight(setId: String, text: String) = editDraft { it.withSet(setId) { set -> set.copy(weight = text) } }

    fun onReps(setId: String, text: String) = editDraft { it.withSet(setId) { set -> set.copy(reps = text) } }

    fun setWarmup(setId: String, warmup: Boolean) = editDraft { it.withSet(setId) { set -> set.copy(warmup = warmup) } }

    fun removeSet(setId: String) = editDraft { it.removeSet(setId) }

    fun moveSet(setId: String, delta: Int) = editDraft { it.moveSet(setId, delta) }

    // ---- Leaving ----

    /** Back / Cancel: asks before throwing away changes; with no changes it just closes. Back again closes the prompt. */
    fun requestExit() {
        val e = editing.value
        if (e != null && e.confirmingExit) {
            keepEditing()
        } else if (e != null && isDirty(e) && !transient.value.conflict && !transient.value.gone) {
            edit { it.copy(confirmingExit = true) }
        } else {
            exitFlag.value = true
        }
    }

    fun keepEditing() = edit { it.copy(confirmingExit = false) }

    /** Closes without saving: nothing is written. */
    fun discard() {
        edit { it.copy(confirmingExit = false) }
        exitFlag.value = true
    }

    fun dismissSaveError() = transient.update { it.copy(saveFailed = false) }

    // ---- Save ----

    /** Validates everything and saves the whole edit as one mutation; repeated taps are ignored. */
    fun save() {
        val e = editing.value ?: return
        val t = transient.value
        if (t.saving || t.conflict || t.gone || exitFlag.value) return
        val start = resolveStart(e)
        if (start == null) {
            edit { it.copy(showIssues = true) }
            return
        }
        val draft = e.draft.copy(startedAt = start)
        transient.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            try {
                when (val result = saveDraft(e.original, draft)) {
                    is SaveResult.Saved -> {
                        savedScopes.value = result.invalidatedScopes
                        exitFlag.value = true
                    }
                    is SaveResult.Invalid -> editing.value?.let { current ->
                        // Not through edit(): user edits are frozen while saving, this result is not one.
                        set(current.copy(showIssues = true, referenceErrors = result.summary.errors.filter { it.needsStorage() }))
                    }
                    SaveResult.Conflict -> transient.update { it.copy(conflict = true) }
                    SaveResult.NotFound -> transient.update { it.copy(gone = true) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                transient.update { it.copy(saveFailed = true) }
            } finally {
                transient.update { it.copy(saving = false) }
            }
        }
    }

    // ---- Internals ----

    /** The edited start: the original instant while the texts are untouched (keeps its seconds). */
    private fun resolveStart(e: Editing): Instant? {
        val original = Instant.ofEpochMilli(e.original.session.startedAt).atZone(zone)
        if (e.dateText == original.format(dateFormat) && e.timeText == original.format(timeFormat)) return e.draft.startedAt
        return try {
            LocalDate.parse(e.dateText.trim(), dateFormat).atTime(LocalTime.parse(e.timeText.trim(), timeFormat)).atZone(zone).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun isDirty(e: Editing): Boolean {
        val original = Instant.ofEpochMilli(e.original.session.startedAt).atZone(zone)
        return e.dateText != original.format(dateFormat) || e.timeText != original.format(timeFormat) ||
            e.draft != WorkoutDraft.from(e.original)
    }

    private fun DraftError.needsStorage(): Boolean = this is DraftError.GymUnavailable || this is DraftError.UnknownExercise

    private fun issuesOf(e: Editing): List<EditorIssue> {
        val start = resolveStart(e)
        val draft = if (start != null) e.draft.copy(startedAt = start) else e.draft
        val values = WorkoutDraftValidation.validateValues(draft, now()).filterNot { start == null && it.isDateError() }
        // Reference errors stay only while the row / gym they name is still in the draft.
        val references = e.referenceErrors.filter { error ->
            when (error) {
                is DraftError.GymUnavailable -> error.gymId == e.draft.gymId
                is DraftError.UnknownExercise -> e.draft.exercises.any { it.id == error.loggedExerciseId }
                else -> false
            }
        }
        return buildList {
            if (start == null) add(EditorIssue.DateUnreadable)
            (values + references).forEach { error -> add(error.toIssue(e.draft)) }
        }
    }

    private fun DraftError.isDateError() = this == DraftError.DateInFuture || this == DraftError.DateOutOfRange

    private fun DraftError.toIssue(draft: WorkoutDraft): EditorIssue = when (this) {
        DraftError.DateInFuture -> EditorIssue.DateInFuture
        DraftError.DateOutOfRange -> EditorIssue.DateOutOfRange
        is DraftError.GymUnavailable -> EditorIssue.GymUnavailable
        DraftError.NotesTooLong -> EditorIssue.NotesTooLong
        is DraftError.UnknownExercise -> EditorIssue.UnknownExercise(draft.exercises.indexOfFirst { it.id == loggedExerciseId } + 1)
        is DraftError.InvalidWeight -> setPosition(draft, setId).let { (name, number) -> EditorIssue.InvalidWeight(name, number, error) }
        is DraftError.InvalidReps -> setPosition(draft, setId).let { (name, number) -> EditorIssue.InvalidReps(name, number, error) }
    }

    private fun setPosition(draft: WorkoutDraft, setId: String): Pair<String?, Int> {
        draft.exercises.forEach { exercise ->
            val index = exercise.sets.indexOfFirst { it.id == setId }
            if (index >= 0) return exercise.exerciseName to index + 1
        }
        return null to 0
    }

    private fun render(e: Editing?, t: Transient, gyms: List<Gym>): HistoryEditorUiState {
        if (e == null) return HistoryEditorUiState(status = t.status)
        val issues = issuesOf(e)
        return HistoryEditorUiState(
            status = t.status,
            dateText = e.dateText,
            timeText = e.timeText,
            dateUnreadable = resolveStart(e) == null,
            gymId = e.draft.gymId,
            gyms = gyms,
            currentGymName = e.originalGymName.takeIf { e.draft.gymId == e.original.session.gymId },
            notes = e.draft.notes,
            notesTooLong = (WorkoutDraftValidation.cleanNotes(e.draft.notes)?.length ?: 0) > WorkoutLimits.NOTES_MAX,
            exercises = e.draft.exercises.mapIndexed { index, exercise ->
                EditorExerciseUi(
                    id = exercise.id,
                    name = exercise.exerciseName,
                    sets = exercise.sets.mapIndexed { setIndex, set ->
                        EditorSetUi(
                            id = set.id,
                            number = setIndex + 1,
                            weightText = set.weight,
                            repsText = set.reps,
                            warmup = set.warmup,
                            weightError = (SetValidation.parseWeight(set.weight) as? FieldParse.Invalid)?.error,
                            repsError = (SetValidation.parseReps(set.reps) as? FieldParse.Invalid)?.error,
                        )
                    },
                    isFirst = index == 0,
                    isLast = index == e.draft.exercises.lastIndex,
                )
            },
            dirty = isDirty(e),
            pickerOpen = e.pickerOpen,
            pickerQuery = e.pickerQuery,
            saving = t.saving,
            issues = issues,
            showIssues = e.showIssues && issues.isNotEmpty(),
            confirmingExit = e.confirmingExit,
            conflict = t.conflict,
            gone = t.gone,
            saveFailed = t.saveFailed,
        )
    }

    private fun editDraft(transform: (WorkoutDraft) -> WorkoutDraft) = edit { it.copy(draft = transform(it.draft)) }

    private fun edit(transform: (Editing) -> Editing) {
        val current = editing.value ?: return
        if (transient.value.saving) return
        set(transform(current))
    }

    private fun set(value: Editing?) {
        editing.value = value
        persist(value)
    }

    private fun persist(e: Editing?) {
        if (e == null) {
            KEYS.forEach { savedState.remove<Any>(it) }
            return
        }
        savedState[KEY_ORIGINAL] = WorkoutDraftCodec.encode(e.original)
        savedState[KEY_GYM_NAME] = e.originalGymName
        savedState[KEY_DRAFT] = WorkoutDraftCodec.encode(e.draft)
        savedState[KEY_DATE] = e.dateText
        savedState[KEY_TIME] = e.timeText
        savedState[KEY_PICKER_OPEN] = e.pickerOpen
        savedState[KEY_PICKER_QUERY] = e.pickerQuery
        savedState[KEY_SHOW_ISSUES] = e.showIssues
        savedState[KEY_CONFIRM_EXIT] = e.confirmingExit
    }

    private fun restore(): Editing? {
        val original = WorkoutDraftCodec.decodeAggregate(savedState[KEY_ORIGINAL]) ?: return null
        val draft = WorkoutDraftCodec.decodeDraft(savedState[KEY_DRAFT]) ?: return null
        if (original.session.id != sessionId || draft.sessionId != sessionId) return null
        return Editing(
            original = original,
            originalGymName = savedState[KEY_GYM_NAME],
            draft = draft,
            dateText = savedState[KEY_DATE] ?: return null,
            timeText = savedState[KEY_TIME] ?: return null,
            pickerOpen = savedState[KEY_PICKER_OPEN] ?: false,
            pickerQuery = savedState[KEY_PICKER_QUERY] ?: "",
            showIssues = savedState[KEY_SHOW_ISSUES] ?: false,
            confirmingExit = savedState[KEY_CONFIRM_EXIT] ?: false,
        )
    }

    private companion object {
        const val MAX_PICKER_RESULTS = 30
        const val KEY_ORIGINAL = "historyEditor.original"
        const val KEY_GYM_NAME = "historyEditor.gymName"
        const val KEY_DRAFT = "historyEditor.draft"
        const val KEY_DATE = "historyEditor.date"
        const val KEY_TIME = "historyEditor.time"
        const val KEY_PICKER_OPEN = "historyEditor.pickerOpen"
        const val KEY_PICKER_QUERY = "historyEditor.pickerQuery"
        const val KEY_SHOW_ISSUES = "historyEditor.showIssues"
        const val KEY_CONFIRM_EXIT = "historyEditor.confirmExit"
        val KEYS = listOf(
            KEY_ORIGINAL, KEY_GYM_NAME, KEY_DRAFT, KEY_DATE, KEY_TIME, KEY_PICKER_OPEN, KEY_PICKER_QUERY, KEY_SHOW_ISSUES, KEY_CONFIRM_EXIT,
        )
    }
}
