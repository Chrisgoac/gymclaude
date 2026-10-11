# GymLog Android Native Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship one public, fully native GymLog Android application with complete PWA parity, offline-first cloud sync, full workout-history editing, private media, Pro AI, verified Google Play Billing, and Play-ready release assets.

**Architecture:** Keep the Next.js application as the versioned mobile backend and add a Kotlin/Jetpack Compose application under `android/`. Execute six dependency-ordered plans: first make cloud contracts safe, then establish the Android platform, port the free product, add insights and settings, integrate media and AI, and finally add Billing/privacy/release controls. Every public capability lands in the same production AAB; intermediate builds are internal or closed-test builds only.

**Tech Stack:** Next.js 16 route handlers, TypeScript, Zod, Drizzle/Postgres, Clerk, Cloudflare R2, DeepSeek; Kotlin 2.3, Android Gradle Plugin 9.2, Jetpack Compose BOM 2026.08, Room 2.8, WorkManager 2.11, Ktor 3.5, DataStore 1.2, Clerk Android 1.0, Play Billing 9.1.

**Spec:** `docs/superpowers/specs/2026-08-31-gymlog-android-nativo-design.md`

## Global Constraints

- Android only: no WebView, Capacitor, React Native, Flutter, Kotlin Multiplatform, or iOS abstraction.
- Application/package ID is `com.chrisgoac.gymlog`; changing it after the first Play upload is out of scope.
- `minSdk = 26`, `targetSdk = 36`; `compileSdk` may be newer when required by stable AndroidX libraries.
- The APK reads product state only from Room. Network responses are applied to Room before the UI observes them.
- Each authenticated Clerk `userId` gets a physically separate Room database named from a SHA-256 digest.
- A local domain change and its outbox entry are committed in the same Room transaction.
- Backend identity always comes from the verified Clerk bearer token, never from request JSON.
- `SubscriptionEntitlement`, `AIReport`, and `ReportedAIResponse` are server-owned and cannot enter generic sync push.
- Existing PWA endpoints remain operational until the Android production release is accepted.
- Free includes all user data, sync, photos, backup, workouts, history editing, progress, body, and achievements. Pro gates only Coach, AI mesocycles, and intelligent reports.
- No user-authored data is deleted or overwritten by migration scripts without a tested rollback path and an explicit production deployment decision.
- Each phase must finish with green tests and its exit gate before the next phase starts.

---

## Dependency Map

| Order | Plan | Consumes | Produces | Exit gate |
|---|---|---|---|---|
| 1 | [Backend sync and contracts](./2026-08-31-gymlog-android-01-backend-sync.md) | Existing Drizzle schema and legacy sync behavior | Validated `/api/v1/sync`, global cursor, idempotency, shared fixtures | A TypeScript contract test restores every synchronized entity and rejects cross-user access |
| 2 | [Android platform foundation](./2026-08-31-gymlog-android-02-platform-foundation.md) | Plan 1 DTOs and fixtures | Compiling native app, auth, per-user Room, outbox, bootstrap, WorkManager | Existing Clerk test account reaches native shell after a complete Room bootstrap |
| 3 | [Core workouts and history](./2026-08-31-gymlog-android-03-core-workouts.md) | Platform repositories and sync transaction API | Exercises, routines, training, timer, history, complete editor | Offline workout and offline historical edit survive process death and later converge |
| 4 | [Insights, body, achievements, settings](./2026-08-31-gymlog-android-04-insights-settings.md) | Completed workout model and shared parity fixtures | Progress, calculations, body, achievements, gyms, preferences, backup | Kotlin outputs match TypeScript fixtures and all remaining free non-media features are native |
| 5 | [Private media and Pro AI](./2026-08-31-gymlog-android-05-media-ai.md) | Authenticated API client, entitlement reader, user repositories | R2 signed media flow, offline image queue, Coach, mesocycles, reports, reporting | Photo and AI E2E paths pass; server blocks AI without Pro |
| 6 | [Billing, privacy, and Play release](./2026-08-31-gymlog-android-06-billing-release.md) | Finished product and server entitlement checks | Verified Billing, deletion, policies, release build, Play checklist | Signed AAB and all publication evidence are ready; no critical pre-launch blocker remains |

## Shared Contracts

All plans use these exact cross-boundary shapes. Plan 1 owns the TypeScript schemas; Plan 2 mirrors them in Kotlin and executes the same JSON fixtures.

```ts
type SyncTableName =
  | "exercises" | "routines" | "routineExercises" | "workoutSessions"
  | "loggedExercises" | "loggedSets" | "gyms" | "exercisePhotos"
  | "userSettings" | "coachMessages" | "bodyMetrics" | "progressPhotos"
  | "mesocycles" | "achievements";

interface PushRequest { mutations: PushMutationDto[] }
interface PushMutationDto {
  mutationId: string;
  deviceId: string;
  operations: SyncOperationDto[];
}
interface SyncOperationDto {
  table: SyncTableName;
  record: Record<string, unknown> & {
    id: string;
    updatedAt: number;
    deletedAt: number | null;
  };
  baseServerVersion: string | null;
}
interface PushResponse {
  acceptedMutationIds: string[];
  conflicts: SyncConflictDto[];
}
interface PullResponse {
  changes: SyncChangeDto[];
  cursor: string;
  hasMore: boolean;
}
interface ApiErrorBody {
  error: {
    code: string;
    message: string;
    retryable: boolean;
    details?: Record<string, string[]>;
  };
}
```

`serverVersion` is a PostgreSQL `bigint` serialized as a base-10 JSON string. The pull cursor is opaque base64url text and decodes only on the server. Shared fixtures live in `contracts/v1/fixtures/` and are loaded by both Vitest and Kotlin/JVM tests.

Android infrastructure exposes these stable interfaces to features:

```kotlin
interface AuthSessionProvider {
    val state: StateFlow<AuthState>
    suspend fun accessToken(): String?
}

interface SyncApi {
    suspend fun push(request: PushRequest): PushResponse
    suspend fun pull(cursor: String?, limit: Int = 500): PullResponse
}

interface SyncRepository {
    fun observeStatus(): Flow<SyncStatus>
    suspend fun syncOnce(): SyncResult
}

interface UserDatabaseManager {
    suspend fun open(userId: String): GymLogDatabase
    suspend fun closeAndDelete(userId: String)
}
```

## Specification Coverage Audit

| Spec sections | Owning plan/tasks |
|---|---|
| 1–5 goals, constraints, repository, state flow | Roadmap global constraints; Plan 2 Tasks 1–8 |
| 6 UI inventory and Hierro nativo | Plan 2 Task 3; Plan 3 Tasks 1–8; Plan 4 Tasks 3–7; Plan 5 Tasks 4, 7–8 |
| 7 complete history editing | Plan 3 Tasks 7–9; Plan 4 Task 8 |
| 8 entities, schema drift, per-user storage | Plan 1 Tasks 1–2; Plan 2 Tasks 2 and 4 |
| 9 versioned mobile API | Plan 1 Tasks 1, 3–7; Plan 5 Tasks 1–2 and 5–8; Plan 6 Tasks 1–2 and 5 |
| 10 offline sync/bootstrap/conflicts | Plan 1 Tasks 2, 4–8; Plan 2 Tasks 4, 7–9 |
| 11 private photos/files | Plan 5 Tasks 1–4 and 9 |
| 12 Clerk authentication/session | Plan 1 Task 3; Plan 2 Tasks 5–6 and 9; Plan 6 Task 6 |
| 13 Pro and Play Billing | Plan 5 Task 5; Plan 6 Tasks 1–4 and 9 |
| 14 AI safety/reporting | Plan 5 Tasks 5–9; Plan 6 Task 7 |
| 15 privacy/security/deletion | Plan 5 Tasks 1–5; Plan 6 Tasks 5–7 and 9 |
| 16 error semantics | Plan 1 Tasks 3–6; Plan 2 Tasks 5, 7–8; Plan 5 Tasks 3 and 6–8 |
| 17 testing | Shared phase verification tasks and Plan 6 Task 9 |
| 18 publication | Plan 6 Tasks 7–10 |
| 19 migration/coexistence | Plan 1 Tasks 2, 6 and 8; phase gates |
| 20 acceptance | Roadmap Final Acceptance; Plan 6 Tasks 9–10 |
| 21 risks | Contract, convergence, privacy, Billing, and release tests across Plans 1–6 |
| 22 official references | Version-pinned stacks and Play documentation review in Plans 2 and 6 |

## Phase Gates

### Backend gate

- [x] Run `npx vitest run lib/api/v1 db app/api/v1`.
- [x] Run `npm run lint`.
- [x] Run `npm run build`.
- [x] Confirm legacy PWA sync route tests remain green.
- [x] Record row counts for all 14 synchronized tables on a test account before and after bootstrap.

Evidence: [android-phase-01-backend.md](../../verification/android-phase-01-backend.md) (commit `12af079`).

### Android foundation gate

- [ ] Run `cd android && ./gradlew testDebug lintDebug assembleDebug`.
- [ ] Run instrumentation smoke tests on API 26 and API 36.
- [ ] Confirm auth waits for an active Clerk session, not merely a loaded user.
- [ ] Confirm switching test accounts never exposes the previous account's Room rows or cached media.

### Core workouts gate (Plan 3)

Status: **automated evidence complete; device checks pending.**

- [x] Offline routine workout (log, reorder, kill/reopen, finish) and offline historical edit converge on a second device/database, tombstones included (JVM two-device harness against an in-process contract-faithful sync server).
- [x] A duplicated push delivery is applied once on the server (mutationId replay).
- [x] Concurrent offline edits of the same set converge to the LWW winner in either sync order.
- [x] UI offline flow (start routine/free workout, log, finish, history, edit, save) passes on the API 36 emulator.
- [x] No WebView dependency in `android/`; all screens are Compose.
- [ ] API 26 run, Pixel/Samsung rest-timer checks, and a real two-device run against the production server after Clerk configuration.

Evidence: [android-phase-03-workouts.md](../../verification/android-phase-03-workouts.md).

### Product parity gate

- [ ] Complete the inventory in design spec section 6 without WebView screens.
- [ ] Run the six critical E2E flows from design spec section 17.4.
- [ ] Verify TalkBack, 200% font scale, touch targets, light/dark themes, and gesture insets.
- [ ] Compare TypeScript and Kotlin results for every shared domain and conflict fixture.

### Publication gate

- [ ] Produce a signed, minified AAB targeting API 36.
- [ ] Pass Play pre-launch report without critical stability, compatibility, privacy, or accessibility findings.
- [ ] Verify Data Safety, privacy policy, account-deletion URL, AI reporting, Billing products, and test credentials.
- [ ] If the Play account is subject to the personal-account rule, complete 12 opted-in testers for 14 continuous days before production application.
- [ ] Obtain explicit owner approval before pressing the irreversible Play Console production submission action.

## Final Acceptance

- [ ] Every criterion in design spec section 20 has linked test evidence.
- [ ] No unfinished-marker scan matches: `rg -n "[T]BD|[T]ODO|[F]IXME|place''holder|coming[ ]soon" android app/api/v1 lib/api/v1 contracts docs/play`.
- [ ] `git diff --check` reports no whitespace errors.
- [ ] The PWA still builds during the coexistence window.
- [ ] The production AAB is the only public Android edition; Pro is an entitlement, not a separate APK.
