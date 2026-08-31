# GymLog Android Platform Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a compiling native Android shell that authenticates with the existing Clerk instance, opens an isolated Room database for the active user, bootstraps all cloud data, and maintains an idempotent WorkManager sync loop.

**Architecture:** Create a modular Kotlin/Compose project in `android/` with manual dependency injection through `AppContainer`. Domain models and repositories stay platform-independent; Room, Ktor, Clerk, WorkManager, and DataStore are confined to core infrastructure modules. A root state machine prevents navigation until Clerk has an active session and the selected user's bootstrap checkpoint is complete.

**Tech Stack:** Kotlin 2.3, AGP 9.2, Gradle 9.4, Compose BOM 2026.08, Material 3, Room 2.8.4, WorkManager 2.11.2, Navigation 2.9.8, Ktor 3.5.2, kotlinx.serialization/coroutines 1.11, DataStore 1.2.1, Clerk Android 1.0.39, JUnit, Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-08-31-gymlog-android-nativo-design.md`

## Global Constraints

- Use `com.chrisgoac.gymlog` for namespace and application ID.
- Use stable libraries only and commit the Gradle wrapper; never reference dynamic `+` versions.
- Use manual DI; feature modules receive interfaces and do not import Room, Ktor, Clerk, or Billing.
- Never commit Clerk keys. Read publishable key and API base URL from Gradle/local properties injected into `BuildConfig`.
- Gate product navigation on an active Clerk session plus completed bootstrap; a loaded Clerk user without a session is insufficient.
- Room file names use a SHA-256 digest prefix of Clerk `userId`, not raw identifiers.
- Closing/signing out resolves outbox state first, then deletes that user's DB, DataStore scope, and private cache only after explicit confirmation when changes remain.

---

### Task 1: Scaffold the Android build and module graph

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/gradle/libs.versions.toml`
- Create: `android/gradlew`
- Create: `android/gradlew.bat`
- Create: `android/gradle/wrapper/gradle-wrapper.properties`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/kotlin/com/chrisgoac/gymlog/GymLogApplication.kt`
- Create: one `build.gradle.kts` for every module named in design spec section 5.1
- Create: `.github/workflows/android.yml`

**Interfaces:** `core:model` is the base; `core:domain` depends on model; `core:database` and `core:network` depend on model; `core:data` depends on domain plus those infrastructure modules; features depend only on domain, model, and designsystem; `app` wires implementations. `core:testing` is test-only.

- [ ] Add a root Gradle test that asserts `:app` and every declared module appears in `gradlew projects`.
- [ ] Run `cd android && ./gradlew projects`; confirm failure before scaffolding.
- [ ] Create wrapper, version catalog, namespace, `minSdk 26`, `targetSdk 36`, release/debug build types, Java/Kotlin 17, Compose compiler plugin, and empty modules.
- [ ] Add CI jobs for `testDebug`, `lintDebug`, and `assembleDebug` using JDK 17 and an Android SDK cache.
- [ ] Run `cd android && ./gradlew projects assembleDebug`.
- [ ] Commit: `git add android .github/workflows/android.yml && git commit -m "build(android): scaffold native modular app"`.

### Task 2: Mirror v1 models and execute shared JSON fixtures

**Files:**
- Create: `android/core/model/src/main/kotlin/com/chrisgoac/gymlog/model/Entities.kt`
- Create: `android/core/model/src/main/kotlin/com/chrisgoac/gymlog/model/SyncModels.kt`
- Create: `android/core/model/src/main/kotlin/com/chrisgoac/gymlog/model/ApiError.kt`
- Create: `android/core/model/src/test/kotlin/com/chrisgoac/gymlog/model/SyncContractTest.kt`
- Modify: `android/core/model/build.gradle.kts`

**Interfaces:** Kotlin `@Serializable` DTOs mirror roadmap contracts. `serverVersion: Long?` uses a custom decimal-string serializer; unknown polymorphic `table` values fail decoding. Domain entity IDs are inline value classes where serialization remains exact.

- [ ] Write `SyncContractTest` to load `../../../contracts/v1/fixtures/*.json`, decode the aggregate bootstrap and pagination fixtures, re-encode them, and reject the invalid server-field fixture.
- [ ] Run `cd android && ./gradlew :core:model:testDebugUnitTest`; confirm failure.
- [ ] Implement entity and sync DTOs with explicit `@SerialName` mappings, including all corrected drift fields.
- [ ] Configure test resources to include repository `contracts/v1/fixtures` without copying them.
- [ ] Run Kotlin fixture tests and the matching TypeScript fixture test.
- [ ] Commit: `git add android/core/model android/gradle/libs.versions.toml && git commit -m "feat(android): add shared sync models"`.

### Task 3: Build the Hierro nativo design system and navigation shell

**Files:**
- Create: `android/core/designsystem/src/main/kotlin/com/chrisgoac/gymlog/designsystem/theme/Color.kt`
- Create: `android/core/designsystem/src/main/kotlin/com/chrisgoac/gymlog/designsystem/theme/Type.kt`
- Create: `android/core/designsystem/src/main/kotlin/com/chrisgoac/gymlog/designsystem/theme/Theme.kt`
- Create: `android/core/designsystem/src/main/kotlin/com/chrisgoac/gymlog/designsystem/component/Components.kt`
- Create: `android/app/src/main/kotlin/com/chrisgoac/gymlog/navigation/GymLogDestination.kt`
- Create: `android/app/src/main/kotlin/com/chrisgoac/gymlog/navigation/GymLogNavHost.kt`
- Create: `android/app/src/main/kotlin/com/chrisgoac/gymlog/MainActivity.kt`
- Create: `android/app/src/androidTest/kotlin/com/chrisgoac/gymlog/NavigationSmokeTest.kt`

**Interfaces:** `GymLogDestination` defines exactly `Train`, `Routines`, `Exercises`, `Progress`, `History` as bottom destinations and typed secondary routes for Coach, Body, Achievements, and Settings.

- [ ] Write a Compose test asserting five bottom destinations, selected semantics, preserved tab state, 48dp minimum targets, and readable content at 200% font scale.
- [ ] Run `./gradlew :app:connectedDebugAndroidTest`; confirm failure on the missing shell.
- [ ] Implement light/dark warm concrete palettes, safety-orange accents, hard borders/shadows, typography, buttons, cards, fields, empty/error/loading states, system insets, and the five-tab scaffold.
- [ ] Add temporary accessible screen labels only as route smoke content; do not add fake product functionality.
- [ ] Run screenshot/manual checks in light/dark and the navigation test on API 26 and 36.
- [ ] Commit: `git add android/core/designsystem android/app && git commit -m "feat(android): add Hierro native shell"`.

### Task 4: Implement Room, DAOs, outbox, and per-user database management

**Files:**
- Create: `android/core/database/src/main/kotlin/com/chrisgoac/gymlog/database/entity/Entities.kt`
- Create: `android/core/database/src/main/kotlin/com/chrisgoac/gymlog/database/dao/DomainDaos.kt`
- Create: `android/core/database/src/main/kotlin/com/chrisgoac/gymlog/database/dao/SyncDao.kt`
- Create: `android/core/database/src/main/kotlin/com/chrisgoac/gymlog/database/GymLogDatabase.kt`
- Create: `android/core/database/src/main/kotlin/com/chrisgoac/gymlog/database/UserDatabaseManagerImpl.kt`
- Create: `android/core/database/src/test/kotlin/com/chrisgoac/gymlog/database/OutboxTransactionTest.kt`
- Create: `android/core/database/src/androidTest/kotlin/com/chrisgoac/gymlog/database/UserIsolationTest.kt`

**Interfaces:**

```kotlin
@Entity(tableName = "outbox_mutations")
data class OutboxMutationEntity(
    @PrimaryKey val mutationId: String,
    val deviceId: String,
    val payloadJson: String,
    val createdAt: Long,
    val attemptCount: Int,
    val lastErrorCode: String?
)

interface LocalMutationRunner {
    suspend fun <T> run(mutation: LocalMutation<T>): T
}
```

- [ ] Write tests that rollback domain rows when outbox insertion fails, preserve one logical history mutation as one outbox row, retain tombstones, and isolate two user databases.
- [ ] Run `./gradlew :core:database:testDebugUnitTest :core:database:connectedDebugAndroidTest`; confirm failure.
- [ ] Add Room entities/foreign keys/indices for all 14 synchronized types, sync cursor/checkpoint tables, quarantined mutation state, and explicit mappers.
- [ ] Implement `UserDatabaseManagerImpl` with SHA-256 filenames, one active DB, close-before-switch, and delete-by-exact-path.
- [ ] Export Room schemas to `android/core/database/schemas/` for future migration testing.
- [ ] Run tests and inspect exported schema for all drift fields and indices.
- [ ] Commit: `git add android/core/database && git commit -m "feat(android): add isolated Room and durable outbox"`.

### Task 5: Implement the Ktor client and bearer-session boundary

**Files:**
- Create: `android/core/network/src/main/kotlin/com/chrisgoac/gymlog/network/AuthSessionProvider.kt`
- Create: `android/core/network/src/main/kotlin/com/chrisgoac/gymlog/network/GymLogHttpClient.kt`
- Create: `android/core/network/src/main/kotlin/com/chrisgoac/gymlog/network/SyncApiImpl.kt`
- Create: `android/core/network/src/main/kotlin/com/chrisgoac/gymlog/network/NetworkErrorMapper.kt`
- Create: `android/core/network/src/test/kotlin/com/chrisgoac/gymlog/network/SyncApiTest.kt`

**Interfaces:** Implement roadmap `AuthSessionProvider` and `SyncApi`. `SyncApiImpl` adds bearer token per request, has bounded connect/read timeouts, never retries unsafe calls itself, maps stable API errors, and treats `401/403` as `SessionRequired`.

- [ ] Write Ktor `MockEngine` tests for token header, push JSON, multi-page pull, 401, 429 retry metadata, malformed JSON, and no-token short circuit.
- [ ] Run `./gradlew :core:network:testDebugUnitTest`; confirm failure.
- [ ] Implement client plugins for JSON and request IDs; keep retry/backoff in repository/WorkManager.
- [ ] Redact authorization headers and bodies from debug logging.
- [ ] Run tests.
- [ ] Commit: `git add android/core/network && git commit -m "feat(android): add authenticated sync client"`.

### Task 6: Integrate Clerk with a native auth state machine

**Files:**
- Create: `android/app/src/main/kotlin/com/chrisgoac/gymlog/auth/ClerkAuthSessionProvider.kt`
- Create: `android/feature/auth/src/main/kotlin/com/chrisgoac/gymlog/auth/AuthViewModel.kt`
- Create: `android/feature/auth/src/main/kotlin/com/chrisgoac/gymlog/auth/AuthScreen.kt`
- Create: `android/feature/auth/src/test/kotlin/com/chrisgoac/gymlog/auth/AuthViewModelTest.kt`
- Create: `android/app/src/main/kotlin/com/chrisgoac/gymlog/RootStateMachine.kt`

**Interfaces:** `RootState` is `Loading | SignedOut | Bootstrapping(userId, progress) | Ready(userId) | ReauthenticationRequired(userId) | Fatal(message)`. `ClerkAuthSessionProvider.accessToken()` returns a token only from the active session.

- [ ] Test that a loaded user with no active session stays signed out, expired session preserves the DB and yields reauthentication, successful session starts bootstrap once, and account switch closes the first DB.
- [ ] Run auth unit tests; confirm failure.
- [ ] Initialize Clerk from `BuildConfig`, implement native email and configured Google flows with custom Hierro Compose UI, and wire verified redirect/package configuration.
- [ ] Add abuse test notes for registration/recovery rate limiting; do not reimplement Clerk secrets or token storage.
- [ ] Run unit tests and a real-device sign-in against the development Clerk instance.
- [ ] Commit: `git add android/feature/auth android/app && git commit -m "feat(android): add native Clerk authentication"`.

### Task 7: Implement repositories, bootstrap, and sync convergence

**Files:**
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/sync/SyncRepositoryImpl.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/sync/BootstrapRepository.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/sync/ConflictResolver.kt`
- Create: `android/core/data/src/test/kotlin/com/chrisgoac/gymlog/data/sync/SyncRepositoryTest.kt`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/SyncUseCases.kt`

**Interfaces:** `syncOnce()` serializes with a per-user mutex, pushes ordered outbox batches, quarantines non-retryable failures, pulls until `hasMore == false`, applies each page and cursor atomically, and only deletes accepted outbox rows. Bootstrap writes a shadow/checkpoint state and exposes `Ready` only after the last page.

- [ ] Test duplicate push, interrupted page apply, invalid cursor replacement bootstrap, conflict fixture parity, 401 pause, transient exponential backoff input, and a quarantined mutation that does not block later rows.
- [ ] Run `./gradlew :core:data:testDebugUnitTest`; confirm failure.
- [ ] Implement explicit DTO/entity mapping and identical `(updatedAt, deviceId, mutationId)` conflict ordering.
- [ ] Implement bootstrap resume without presenting partial data as complete.
- [ ] Run Kotlin and TypeScript conflict fixtures together.
- [ ] Commit: `git add android/core/data android/core/domain && git commit -m "feat(android): add offline sync and bootstrap"`.

### Task 8: Schedule sync and expose actionable status

**Files:**
- Create: `android/app/src/main/kotlin/com/chrisgoac/gymlog/sync/SyncWorker.kt`
- Create: `android/app/src/main/kotlin/com/chrisgoac/gymlog/sync/SyncScheduler.kt`
- Create: `android/app/src/test/kotlin/com/chrisgoac/gymlog/sync/SyncWorkerTest.kt`
- Create: `android/feature/settings/src/main/kotlin/com/chrisgoac/gymlog/settings/SyncStatusCard.kt`
- Modify: `android/app/src/main/kotlin/com/chrisgoac/gymlog/GymLogApplication.kt`

**Interfaces:** Unique work name is `sync-<userHash>`. Triggers are login/bootstrap, app startup, foreground, connectivity, debounced local mutation, and periodic work. Constraints require network; only one worker runs per user. UI states are `Synced`, `Pending(count)`, `Offline(count)`, `SessionRequired`, and `NeedsAction(count)`.

- [ ] Write WorkManager tests for uniqueness, constraints, retry only on transient failures, success on quarantined rows, and cancellation on sign-out.
- [ ] Run focused tests; confirm failure.
- [ ] Implement immediate and periodic scheduling with exponential backoff and jitter inside supported WorkManager bounds.
- [ ] Add the status card and route it to a diagnostic list without exposing raw payloads/tokens.
- [ ] Run `./gradlew testDebug lintDebug assembleDebug` and API 26/36 smoke tests.
- [ ] Commit: `git add android/app android/feature/settings && git commit -m "feat(android): schedule resilient background sync"`.

### Task 9: Verify the platform phase

**Files:**
- Create: `docs/verification/android-phase-02-platform.md`
- Modify: `docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md`

- [ ] Sign in with an existing test Clerk account and record cloud/Room row counts for all 14 tables after bootstrap.
- [ ] Kill the process mid-bootstrap, reopen, and prove a safe resume.
- [ ] Make a local synthetic mutation offline, restart, reconnect, and prove one cloud write despite duplicate worker delivery.
- [ ] Switch between two test accounts and verify separate database filenames and zero row/cache crossover.
- [ ] Record commands, device/API levels, commit SHA, and sanitized results.
- [ ] Mark the Android foundation gate complete and commit: `git add docs/verification docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md && git commit -m "docs: record Android platform verification"`.
