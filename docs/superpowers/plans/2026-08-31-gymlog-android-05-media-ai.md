# GymLog Android Private Media and Pro AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver private, offline-capable exercise/progress photos and the three server-authorized Pro AI features: Coach, mesocycle generation, and intelligent reports, including in-app response reporting.

**Architecture:** R2 objects become private and synchronized rows store stable object keys only. Android uses Photo Picker/camera, private app storage, a durable upload queue, signed read URLs, and private thumbnail caching. AI remains server-only behind an `EntitlementReader`; Android persists user/assistant message state through Room and treats streams as resumable outcomes, never as the source of truth.

**Tech Stack:** Next.js route handlers, Clerk, Drizzle/Postgres, Cloudflare R2 signed URLs, DeepSeek streaming, Zod; Android Photo Picker/Camera intent, WorkManager, Ktor streaming, Room, Compose.

**Spec:** `docs/superpowers/specs/2026-08-31-gymlog-android-nativo-design.md`

## Global Constraints

- R2 buckets/objects are private; permanent public URLs may not be stored or returned by v1.
- Synced media rows store `objectKey`, media metadata, and tombstone; signed URLs are short-lived transport data only.
- Android requests no broad gallery/storage permission. Use system Photo Picker or camera capture URI.
- Offline originals and thumbnails live under the exact active user's private directory and are removed after confirmed deletion/sign-out/account deletion.
- DeepSeek keys and prompt construction remain on the server. Coach snapshots never include photos.
- Every AI endpoint validates active Pro entitlement server-side; Android gating is presentation only.
- Partial AI streams never create duplicate user messages on retry. Medical/diagnostic claims are explicitly disclaimed.

---

### Task 1: Make R2 media contracts private and owner-scoped

**Files:**
- Modify: `lib/r2/client.ts`
- Create: `lib/api/v1/media/contracts.ts`
- Create: `lib/api/v1/media/service.ts`
- Create: `lib/api/v1/media/service.test.ts`
- Modify: `db/schema.ts`
- Create: `drizzle/0002_private_media_keys.sql`

**Interfaces:**

```ts
interface MediaUploadResponse {
  mediaId: string;
  objectKey: string;
  readUrl: string;
  readUrlExpiresAt: number;
}
interface MediaReadGrant {
  mediaId: string;
  readUrl: string;
  expiresAt: number;
}
```

Object keys are server-generated under `users/<userHash>/<kind>/<uuid>` and never accepted verbatim from clients. Limits declare allowed MIME types, decoded image dimensions, and compressed byte size.

- [ ] Test upload ownership, magic-byte/MIME mismatch, oversize input, namespace generation, signed URL expiry, cross-user read/delete, duplicate delete, and tombstoned object cleanup.
- [ ] Run `npx vitest run lib/api/v1/media/service.test.ts`; confirm failure.
- [ ] Add object-key columns/backfill-compatible migration and R2 helpers for private put/get signing/delete.
- [ ] Implement service methods and an idempotent delayed object deletion path after row tombstone.
- [ ] Run tests and migration rehearsal against disposable data.
- [ ] Commit: `git add lib/r2 lib/api/v1/media db drizzle && git commit -m "feat(media): make user photos private"`.

### Task 2: Expose exercise and progress media v1 routes

**Files:**
- Create: `app/api/v1/media/exercises/route.ts`
- Create: `app/api/v1/media/exercises/[id]/route.ts`
- Create: `app/api/v1/media/progress/route.ts`
- Create: `app/api/v1/media/progress/[id]/route.ts`
- Create: `app/api/v1/media/read/[id]/route.ts`
- Create: `app/api/v1/media/media-routes.test.ts`
- Modify: `app/api/exercise-photos/route.test.ts`
- Modify: `app/api/progress-photos/route.test.ts`

**Interfaces:** Authenticated multipart POST creates media row/object; authenticated DELETE tombstones row and schedules object deletion; authenticated GET read grant returns a fresh signed URL for owned live media.

- [ ] Test bearer auth, limits before R2 upload, request cancellation cleanup, signed grant, ownership, rate limit seam, stable errors, and legacy PWA regressions.
- [ ] Run route tests; confirm failure.
- [ ] Implement thin route handlers through `withApiRoute()` and the media service.
- [ ] Ensure no response/cache log includes signed query parameters.
- [ ] Run `npx vitest run app/api/v1/media app/api/exercise-photos app/api/progress-photos` plus lint/build.
- [ ] Commit: `git add app/api/v1/media app/api/exercise-photos app/api/progress-photos && git commit -m "feat(api): expose private media v1"`.

### Task 3: Add Android capture, compression, cache, and upload queue

**Files:**
- Create: `android/core/model/src/main/kotlin/com/chrisgoac/gymlog/model/media/MediaModels.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/media/MediaRepository.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/media/ImageCompressor.kt`
- Create: `android/core/network/src/main/kotlin/com/chrisgoac/gymlog/network/MediaApi.kt`
- Create: `android/app/src/main/kotlin/com/chrisgoac/gymlog/media/MediaUploadWorker.kt`
- Create: `android/core/data/src/test/kotlin/com/chrisgoac/gymlog/data/media/MediaRepositoryTest.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:** `enqueue(kind, sourceUri, ownerEntityId)` copies into the active user's private pending directory before returning, compresses with orientation preserved, creates a local pending row/outbox state, and schedules unique upload. Confirmed upload swaps local-pending reference for object key and removes temporary original.

- [ ] Test EXIF orientation, dimensions/quality bounds, corrupt/unsupported input, offline enqueue, process death, duplicate worker, permanent rejection preserving actionable original, successful cleanup, signed URL expiry, and account cache isolation.
- [ ] Run tests; confirm failure.
- [ ] Implement Photo Picker/camera URI-safe ingest, compression, Ktor multipart, durable worker, expiry-aware private thumbnail cache, and exact-user directory cleanup.
- [ ] Verify manifest requests camera only when camera capture is offered and no broad media/storage permission.
- [ ] Run unit/lint/API 26 and 36 device tests.
- [ ] Commit: `git add android/core android/app && git commit -m "feat(android): add offline private media pipeline"`.

### Task 4: Complete exercise-photo and progress-photo UI

**Files:**
- Modify: `android/feature/exercises/src/main/kotlin/com/chrisgoac/gymlog/exercises/ExerciseDetailScreen.kt`
- Modify: `android/feature/body/src/main/kotlin/com/chrisgoac/gymlog/body/BodyScreen.kt`
- Create: `android/feature/progress/src/main/kotlin/com/chrisgoac/gymlog/progress/ProgressGalleryScreen.kt`
- Create: `android/feature/progress/src/main/kotlin/com/chrisgoac/gymlog/progress/PhotoComparisonScreen.kt`
- Create: `android/feature/progress/src/test/kotlin/com/chrisgoac/gymlog/progress/PhotoGalleryViewModelTest.kt`

**Interfaces:** Media UI states are `LocalPending`, `Uploading(progress)`, `Available(localThumbnail)`, `RemoteUnavailableOffline`, `RetryableFailure`, and `Rejected(reason)`.

- [ ] Test Picker/camera result, offline preview, upload state, retry/discard, delete confirmation, signed URL refresh, uncached offline state, chronological gallery, and two-photo comparison.
- [ ] Run tests; confirm failure.
- [ ] Implement exercise attachment UI, body/progress capture, gallery, full-screen private viewer, and comparison with accessible date labels.
- [ ] Prevent screen capture on the full-screen private viewer only if product review confirms that tradeoff; otherwise document why normal Android screenshot behavior remains.
- [ ] Run Compose tests and manual offline/online flow.
- [ ] Commit: `git add android/feature/exercises android/feature/body android/feature/progress && git commit -m "feat(android): add private photo experiences"`.

### Task 5: Add server-owned Pro/AI persistence and authorization seam

**Files:**
- Modify: `db/schema.ts`
- Create: `drizzle/0003_ai_and_entitlements.sql`
- Create: `lib/entitlements/reader.ts`
- Create: `lib/entitlements/reader.test.ts`
- Create: `lib/api/v1/ai/contracts.ts`
- Create: `lib/api/v1/ai/guard.ts`
- Create: `lib/api/v1/ai/guard.test.ts`

**Interfaces:** Add server-only `subscription_entitlements`, `ai_reports`, and `reported_ai_responses`. `EntitlementReader.isPro(userId, now): Promise<boolean>` reads active verified entitlement. `requirePro()` returns `403 PRO_REQUIRED`; an unavailable entitlement store fails closed. No table is registered in generic sync.

- [ ] Test active/expired/revoked/missing entitlement, DB failure, cross-user report access, and explicit rejection of the three server-only tables in push.
- [ ] Run tests; confirm failure.
- [ ] Add additive schema/migration, reader, AI Zod schemas, max prompt/snapshot limits, and guard.
- [ ] Provide a documented non-production test entitlement seed command instead of a production bypass.
- [ ] Run migration rehearsal, contract tests, and generic sync rejection tests.
- [ ] Commit: `git add db drizzle lib/entitlements lib/api/v1/ai && git commit -m "feat(ai): add server-owned Pro authorization"`.

### Task 6: Expose safe Coach streaming and response reporting

**Files:**
- Create: `app/api/v1/ai/coach/route.ts`
- Create: `app/api/v1/ai/coach/route.test.ts`
- Create: `app/api/v1/ai/responses/[id]/report/route.ts`
- Create: `app/api/v1/ai/responses/[id]/report/route.test.ts`
- Modify: `lib/coach-prompt.ts`
- Modify: `lib/coach-model.ts`

**Interfaces:** Coach request contains `clientMessageId`, bounded message text, and calculated non-photo snapshot. Stream emits newline-delimited/SSE events `start`, `delta`, `complete(responseId)`, or `error(code,retryable)`. Reporting accepts a fixed category enum and optional bounded context; duplicate reports are idempotent.

- [ ] Test auth/Pro checks, prompt limits, snapshot field allowlist, rate limits, DeepSeek timeout/partial stream, retry by `clientMessageId`, no duplicate user message, disclaimer metadata, report ownership/category/idempotency.
- [ ] Run route tests; confirm failure.
- [ ] Adapt server prompt/model logic to a test-injectable streaming provider and persist complete/incomplete response metadata.
- [ ] Implement routes through authenticated/Pro guards; redact provider and user content from ordinary logs.
- [ ] Run tests, lint, and build.
- [ ] Commit: `git add app/api/v1/ai lib/coach-* && git commit -m "feat(ai): add authorized Coach streaming"`.

### Task 7: Build native Coach with resilient stream state

**Files:**
- Create: `android/core/network/src/main/kotlin/com/chrisgoac/gymlog/network/CoachApi.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/coach/CoachRepository.kt`
- Create: `android/feature/coach/src/main/kotlin/com/chrisgoac/gymlog/coach/CoachScreen.kt`
- Create: `android/feature/coach/src/main/kotlin/com/chrisgoac/gymlog/coach/CoachViewModel.kt`
- Create: `android/feature/coach/src/test/kotlin/com/chrisgoac/gymlog/coach/CoachViewModelTest.kt`

**Interfaces:** One persistent thread per user. Send persists the user message with `clientMessageId` first; stream deltas update an ephemeral response; completion persists assistant response. Interrupted response is marked incomplete and retry reuses `clientMessageId`.

- [ ] Test entitlement presentation, offline/network requirement, suggested prompts, delta accumulation, rotation, interruption, retry without duplicate, report dialog, and historical-edit snapshot refresh.
- [ ] Run tests; confirm failure.
- [ ] Implement Ktor stream parser, repository persistence, Compose conversation, incomplete/error states, disclaimer, and report flow.
- [ ] Ensure tokens/text are absent from logs and screenshots in automated reports.
- [ ] Run unit/Compose tests.
- [ ] Commit: `git add android/core/network android/core/data android/feature/coach && git commit -m "feat(android): add native Pro Coach"`.

### Task 8: Add AI mesocycles and intelligent reports end to end

**Files:**
- Create: `app/api/v1/ai/mesocycles/route.ts`
- Create: `app/api/v1/ai/mesocycles/route.test.ts`
- Create: `app/api/v1/ai/reports/route.ts`
- Create: `app/api/v1/ai/reports/route.test.ts`
- Create: `android/feature/routines/src/main/kotlin/com/chrisgoac/gymlog/routines/MesocycleGeneratorScreen.kt`
- Create: `android/feature/routines/src/main/kotlin/com/chrisgoac/gymlog/routines/MesocycleDetailScreen.kt`
- Create: `android/feature/progress/src/main/kotlin/com/chrisgoac/gymlog/progress/IntelligentReportScreen.kt`
- Create: `android/feature/routines/src/test/kotlin/com/chrisgoac/gymlog/routines/MesocycleGeneratorTest.kt`

**Interfaces:** Mesocycle generation returns a validated draft that the user reviews before saving to synchronized `Mesocycle`; intelligent reports are server-owned, keyed by source-data fingerprint, generated only for Pro, and returned by endpoint rather than generic sync.

- [ ] Test auth/Pro/rate limits, malformed model output, allowed exercises, review-before-save, retry idempotency, weekly progression, report fingerprint cache/invalidation, and medical disclaimer.
- [ ] Run backend/Android tests; confirm failure.
- [ ] Implement strict server output parsing and endpoints, then Android network/repository/ViewModel/screens.
- [ ] Make saving a reviewed mesocycle one local Room/outbox transaction; do not trust model-generated IDs.
- [ ] Run all AI tests, lint, build, and offline UI state tests.
- [ ] Commit: `git add app/api/v1/ai android/feature/routines android/feature/progress android/core && git commit -m "feat(ai): add mesocycles and intelligent reports"`.

### Task 9: Verify media and AI phase

**Files:**
- Create: `docs/verification/android-phase-05-media-ai.md`
- Modify: `docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md`

- [ ] Capture a photo offline, kill/reopen, reconnect, upload once, fetch through a short signed URL, and display its cached thumbnail offline.
- [ ] Prove a second user cannot fetch/read/delete the media ID and cannot see the first user's local cache.
- [ ] Prove Free receives `PRO_REQUIRED` from all three AI endpoints regardless of client UI state.
- [ ] With a seeded test entitlement, run Coach streaming/interruption/retry/report, mesocycle review/save, and report cache invalidation.
- [ ] Run backend suites plus Android API 26/36 tests and record sanitized evidence.
- [ ] Mark the phase gate and commit: `git add docs/verification docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md && git commit -m "test: verify private media and Pro AI"`.
