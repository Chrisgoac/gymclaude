# GymLog Android Core Workouts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the complete native exercises, routines, active training, rest timer, history, and full historical workout editing experience while preserving offline-first transaction and sync behavior.

**Architecture:** Feature ViewModels call pure use cases and repository interfaces; repositories update Room and outbox atomically. A versioned catalog asset provides global exercises offline, while custom exercises remain user rows. Active workouts and history share the same domain aggregate so editing, validation, tombstones, and derived-data invalidation cannot drift.

**Tech Stack:** Kotlin, Compose, Room Flow, WorkManager, DataStore, Android notifications, kotlinx.coroutines, JUnit and Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-08-31-gymlog-android-nativo-design.md`

## Global Constraints

- All ordinary mutations render immediately from Room and never wait for HTTP.
- Global catalog records are APK assets and are never pushed as user data; custom exercises are synchronized.
- Stable IDs survive reorder/edit; only actual removals create tombstones.
- Saving a historical edit emits one logical outbox mutation across session/exercise/set tables.
- Cancelling historical editing writes nothing.
- Weights, repetitions, timestamps, references, and ordering validate before entering the transaction.
- Timer notifications are local and functional only; no promotional or remote notification permission is introduced.

---

### Task 1: Package and validate the offline exercise catalog

**Files:**
- Create: `scripts/export-android-catalog.mjs`
- Create: `android/app/src/main/assets/exercises-v1.json`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/catalog/CatalogImporter.kt`
- Create: `android/core/data/src/test/kotlin/com/chrisgoac/gymlog/data/catalog/CatalogImporterTest.kt`
- Modify: `package.json`

**Interfaces:** `CatalogAsset(version: Int, exercises: List<CatalogExercise>)`; `CatalogImporter.importIfNeeded(database, asset)` upserts only rows with `owner = GLOBAL`, never overwrites custom rows, and stores imported version per user database.

- [ ] Write exporter/Android tests for stable IDs, unique exercise names per catalog identity, required muscle/equipment fields, optional video/description/execution, and idempotent second import.
- [ ] Run Node and Kotlin tests; confirm missing asset/importer failures.
- [ ] Implement deterministic export from the current catalog source and copy licensed local media references only.
- [ ] Implement transactional import on first bootstrap and version upgrade.
- [ ] Run export twice and assert no diff; run importer tests.
- [ ] Commit: `git add scripts package.json android/app/src/main/assets android/core/data && git commit -m "feat(android): package offline exercise catalog"`.

### Task 2: Build exercise repositories and native screens

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/exercise/ExerciseRepository.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/exercise/OfflineExerciseRepository.kt`
- Create: `android/feature/exercises/src/main/kotlin/com/chrisgoac/gymlog/exercises/ExerciseListScreen.kt`
- Create: `android/feature/exercises/src/main/kotlin/com/chrisgoac/gymlog/exercises/ExerciseDetailScreen.kt`
- Create: `android/feature/exercises/src/main/kotlin/com/chrisgoac/gymlog/exercises/ExerciseEditorScreen.kt`
- Create: `android/feature/exercises/src/test/kotlin/com/chrisgoac/gymlog/exercises/ExerciseViewModelTest.kt`

**Interfaces:** Repository observes search/filter results and supports create/update/tombstone only for custom exercises. Domain validation covers name, muscles, equipment, `incrementoKg > 0`, URL syntax, description, and execution.

- [ ] Test accent-insensitive search, filters, global/custom merge, custom CRUD/outbox, global edit rejection, and offline persistence.
- [ ] Run feature tests; confirm failure.
- [ ] Implement repository and ViewModels, then list/detail/editor Compose screens with proper empty/error states and TalkBack labels.
- [ ] Link photo actions to a disabled capability state until Plan 5 rather than a fake upload.
- [ ] Run unit and Compose tests.
- [ ] Commit: `git add android/core/domain android/core/data android/feature/exercises && git commit -m "feat(android): add native exercise management"`.

### Task 3: Build routine creation, editing, ordering, and rotation

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/routine/RoutineRepository.kt`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/routine/RoutineRotation.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/routine/OfflineRoutineRepository.kt`
- Create: `android/feature/routines/src/main/kotlin/com/chrisgoac/gymlog/routines/RoutineListScreen.kt`
- Create: `android/feature/routines/src/main/kotlin/com/chrisgoac/gymlog/routines/RoutineEditorScreen.kt`
- Create: `android/feature/routines/src/test/kotlin/com/chrisgoac/gymlog/routines/RoutineViewModelTest.kt`
- Create: `contracts/domain/routine-rotation.json`

**Interfaces:** Routine aggregate supports archive/tombstone, ordered exercises, series target, `repsObjetivoMin..repsObjetivoMax`, rest seconds, and notes. Reorder updates stable child IDs and compact integer positions in one mutation.

- [ ] Port current TypeScript routine-rotation cases into a shared JSON fixture and run it from TypeScript and Kotlin.
- [ ] Test create/edit/archive/delete/order, invalid repetition range, exercise tombstone handling, and next-routine selection.
- [ ] Run tests; confirm failure.
- [ ] Implement aggregate validation, repository transaction, drag/reorder UI, add-exercise search, and start-routine action.
- [ ] Run tests and a process-recreation Compose test for an unsaved editor draft.
- [ ] Commit: `git add contracts/domain android/core/domain android/core/data android/feature/routines && git commit -m "feat(android): add native routines"`.

### Task 4: Implement active workout aggregate and start flows

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/workout/WorkoutModels.kt`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/workout/WorkoutRepository.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/workout/OfflineWorkoutRepository.kt`
- Create: `android/feature/train/src/main/kotlin/com/chrisgoac/gymlog/train/TrainHomeScreen.kt`
- Create: `android/feature/train/src/main/kotlin/com/chrisgoac/gymlog/train/StartWorkoutUseCase.kt`
- Create: `android/feature/train/src/test/kotlin/com/chrisgoac/gymlog/train/StartWorkoutTest.kt`

**Interfaces:** `startFreeWorkout(gymId)` and `startRoutineWorkout(routineId, gymId)` create session plus children and one outbox mutation. At most one active session exists per user; reopening routes back to it.

- [ ] Test free/routine start, duplicate taps, invalid gym/routine, archived child behavior, one-active-session invariant, and offline restart.
- [ ] Run tests; confirm failure.
- [ ] Implement Room transaction, repository Flow, home cards for next routine/weekly summary/streak/Coach entry, and gym selection.
- [ ] Keep Coach entry visibly Pro but route implementation to Plan 5.
- [ ] Run tests.
- [ ] Commit: `git add android/core/domain android/core/data android/feature/train && git commit -m "feat(android): start offline workouts"`.

### Task 5: Implement fast set logging and progression context

**Files:**
- Create: `android/feature/train/src/main/kotlin/com/chrisgoac/gymlog/train/ActiveWorkoutScreen.kt`
- Create: `android/feature/train/src/main/kotlin/com/chrisgoac/gymlog/train/ExerciseLogCard.kt`
- Create: `android/feature/train/src/main/kotlin/com/chrisgoac/gymlog/train/WorkoutViewModel.kt`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/workout/SetValidation.kt`
- Create: `android/feature/train/src/test/kotlin/com/chrisgoac/gymlog/train/WorkoutViewModelTest.kt`

**Interfaces:** Events include add/remove/reorder exercise, add/remove/reorder set, edit weight/reps/warmup, complete set, and notes. Inputs remain string drafts until valid; only valid committed values write Room. Previous performance and suggestion are read-only flows.

- [ ] Test decimal comma/dot handling, zero/negative/overflow rejection, rapid edits, stable focus, duplicate events, order compaction, previous-set display, and process recreation.
- [ ] Run tests; confirm failure.
- [ ] Implement one-hand UI with large number fields, completed state, targets, prior values, suggestions, exercise search, and durable active-session observation.
- [ ] Batch/debounce high-frequency field commits without risking an unpersisted completed set.
- [ ] Run unit and Compose tests at 200% font scale.
- [ ] Commit: `git add android/feature/train android/core/domain && git commit -m "feat(android): add fast workout logging"`.

### Task 6: Add durable rest timer and local notification

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/timer/RestTimer.kt`
- Create: `android/app/src/main/kotlin/com/chrisgoac/gymlog/timer/RestTimerNotifier.kt`
- Create: `android/feature/train/src/main/kotlin/com/chrisgoac/gymlog/train/RestTimerBar.kt`
- Create: `android/core/domain/src/test/kotlin/com/chrisgoac/gymlog/domain/timer/RestTimerTest.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:** Persist `endsAtEpochMillis`, not a decrementing counter. `remaining(now) = max(0, endsAt-now)`. Notification channel is functional, low-noise, and requested only when needed on Android 13+.

- [ ] Test start/pause/reset/extend, wall-clock advance, process death, time-zone change, exact completion, and no duplicate completion event.
- [ ] Run tests; confirm failure.
- [ ] Implement DataStore timer state, UI bar, haptic completion, and local notification using a safe scheduled/background mechanism supported across API 26–36.
- [ ] Verify foreground/background/device-lock behavior on Pixel and Samsung; document manufacturer limitations.
- [ ] Run tests and lint.
- [ ] Commit: `git add android/core/domain android/app android/feature/train && git commit -m "feat(android): add durable rest timer"`.

### Task 7: Finish workouts and render native history

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/workout/FinishWorkoutUseCase.kt`
- Create: `android/feature/history/src/main/kotlin/com/chrisgoac/gymlog/history/HistoryListScreen.kt`
- Create: `android/feature/history/src/main/kotlin/com/chrisgoac/gymlog/history/HistoryDetailScreen.kt`
- Create: `android/feature/history/src/test/kotlin/com/chrisgoac/gymlog/history/HistoryViewModelTest.kt`

**Interfaces:** Finish sets completion time, validates at least one meaningful set or requests confirmation, persists summary atomically, and clears active state. History filters by period/gym/query and shows complete ordered detail; delete writes tombstones across the aggregate in one mutation.

- [ ] Test finish idempotency, empty confirmation, summary totals, history filters, deleted children, deletion confirmation, and offline persistence.
- [ ] Run tests; confirm failure.
- [ ] Implement finish summary, list filters, detail rows, change-gym shortcut, delete confirmation, and sync status affordance.
- [ ] Run unit/Compose tests.
- [ ] Commit: `git add android/core/domain android/feature/history android/feature/train && git commit -m "feat(android): finish workouts and show history"`.

### Task 8: Implement complete atomic historical editing

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/history/WorkoutDraft.kt`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/history/SaveWorkoutDraftUseCase.kt`
- Create: `android/core/domain/src/test/kotlin/com/chrisgoac/gymlog/domain/history/SaveWorkoutDraftTest.kt`
- Create: `android/feature/history/src/main/kotlin/com/chrisgoac/gymlog/history/HistoryEditorScreen.kt`
- Create: `android/feature/history/src/test/kotlin/com/chrisgoac/gymlog/history/HistoryEditorViewModelTest.kt`

**Interfaces:**

```kotlin
data class WorkoutDraft(
    val sessionId: String,
    val startedAt: Instant,
    val gymId: String?,
    val notes: String,
    val exercises: List<LoggedExerciseDraft>
)

interface SaveWorkoutDraftUseCase {
    suspend operator fun invoke(original: WorkoutAggregate, draft: WorkoutDraft): SaveResult
}
```

`SaveResult` includes invalidated scopes for PRs, 1RM, volume, balance, charts, streaks, weekly summary, stalls, achievements, and Coach snapshot.

- [ ] Test edit date/gym/notes, add/remove/reorder exercises/sets, stable IDs, tombstones, invalid references/values, cancel zero writes, transaction rollback, one outbox mutation, and all invalidation scopes.
- [ ] Run tests; confirm failure.
- [ ] Implement isolated `SavedStateHandle` draft, validation summary, dirty-exit confirmation, and one Room transaction that diffs original/draft.
- [ ] Implement the full Compose editor using the active-workout components in editable-history mode.
- [ ] Run unit/Compose tests including rotation and process recreation.
- [ ] Commit: `git add android/core/domain android/feature/history && git commit -m "feat(android): edit workout history atomically"`.

### Task 9: Verify offline workout convergence

**Files:**
- Create: `android/app/src/androidTest/kotlin/com/chrisgoac/gymlog/WorkoutOfflineE2eTest.kt`
- Create: `docs/verification/android-phase-03-workouts.md`
- Modify: `docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md`

- [ ] Automate start routine offline, log/reorder sets, background timer, kill/reopen, finish, edit historical date/gym/exercises/sets, and reconnect.
- [ ] Deliver the same WorkManager job twice and assert one server mutation result.
- [ ] Pull from a second test device/database and compare the full aggregate plus tombstones.
- [ ] Verify all screens use Compose and no WebView dependency exists in `android/`.
- [ ] Run `./gradlew testDebug lintDebug connectedDebugAndroidTest assembleDebug` on API 26 and 36.
- [ ] Record evidence, mark the phase gate, and commit: `git add android/app/src/androidTest docs/verification docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md && git commit -m "test(android): verify offline workout convergence"`.
