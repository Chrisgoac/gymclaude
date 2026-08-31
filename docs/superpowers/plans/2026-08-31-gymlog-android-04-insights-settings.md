# GymLog Android Insights and Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete native free-feature parity for calculations, progress, body measurements, achievements, gyms, synchronized settings, and explicit backup/import.

**Architecture:** Port current TypeScript calculations into pure Kotlin and make shared JSON fixtures the parity oracle. Derived metrics remain recomputable views over Room, invalidated by workout/history mutations. Compose Canvas supplies dependency-light native charts. Synced preferences remain `UserSetting` rows; device-only preferences live in DataStore.

**Tech Stack:** Kotlin/coroutines, Room, Compose Canvas, DataStore, Storage Access Framework, kotlinx.serialization, JUnit and Compose tests.

**Spec:** `docs/superpowers/specs/2026-08-31-gymlog-android-nativo-design.md`

## Global Constraints

- TypeScript behavior is a starting specification, but shared fixtures record approved outcomes explicitly.
- Calculations use deterministic time zone/clock inputs and do not read the device clock implicitly.
- Charts expose textual summaries and semantics; no metric is available only by color or gesture.
- Reconciliation may add or tombstone achievements but is idempotent on repeated runs.
- Backup never includes Clerk tokens, secrets, raw private cache paths, server-owned entitlements, AI reports, or reported-response records.
- Import validates the complete file before changing Room and commits domain rows plus outbox atomically.

---

### Task 1: Freeze parity fixtures and port numeric/date primitives

**Files:**
- Create: `scripts/export-domain-fixtures.mjs`
- Create: `contracts/domain/period-cases.json`
- Create: `contracts/domain/progression-cases.json`
- Create: `contracts/domain/body-stats-cases.json`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/time/Periods.kt`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/math/Numbers.kt`
- Create: `android/core/domain/src/test/kotlin/com/chrisgoac/gymlog/domain/DomainParityTest.kt`

**Interfaces:** Pure functions accept `Instant`, `ZoneId`, units, and input lists explicitly. Weight is stored in kilograms as `Double`; display conversion never mutates storage values.

- [ ] Export cases from `lib/fecha.test.ts`, `lib/period.test.ts`, `lib/num.test.ts`, `lib/progresion.test.ts`, and `lib/body-stats.test.ts`, then review expected JSON by hand.
- [ ] Write Kotlin parameterized fixture tests and run them; confirm failure.
- [ ] Implement date ranges, ISO week semantics, unit conversion, rounding, estimated 1RM, and progression primitives.
- [ ] Run identical fixtures in Vitest and Kotlin.
- [ ] Commit: `git add scripts contracts/domain android/core/domain && git commit -m "feat(android): port domain calculation primitives"`.

### Task 2: Port statistics, balance, stalls, and weekly insights

**Files:**
- Create: `contracts/domain/insight-cases.json`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/stats/WorkoutStats.kt`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/stats/Insights.kt`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/stats/StatsRepository.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/stats/OfflineStatsRepository.kt`
- Create: `android/core/domain/src/test/kotlin/com/chrisgoac/gymlog/domain/stats/InsightsParityTest.kt`

**Interfaces:** Expose flows for weekly summary, exercise series, PRs, estimated 1RM, volume, muscular balance, streak, stall detection, and progression suggestions filtered by period and gym.

- [ ] Build fixtures from `lib/insights*`, `lib/gym-filter*`, `lib/coach-snapshot*`, and repository stats tests, including empty/one-set/deleted/warmup/time-zone cases.
- [ ] Run parity tests; confirm failure.
- [ ] Implement pure reducers and Room queries with consistent exclusion of tombstones and agreed warmup behavior.
- [ ] Add an invalidation coordinator keyed by `SaveResult` scopes; prove a historical edit refreshes every affected Flow.
- [ ] Run fixture, repository, and invalidation tests.
- [ ] Commit: `git add contracts/domain android/core/domain android/core/data && git commit -m "feat(android): port workout insights"`.

### Task 3: Build accessible native progress screens and charts

**Files:**
- Create: `android/core/designsystem/src/main/kotlin/com/chrisgoac/gymlog/designsystem/chart/LineChart.kt`
- Create: `android/core/designsystem/src/main/kotlin/com/chrisgoac/gymlog/designsystem/chart/BarChart.kt`
- Create: `android/feature/progress/src/main/kotlin/com/chrisgoac/gymlog/progress/ProgressScreen.kt`
- Create: `android/feature/progress/src/main/kotlin/com/chrisgoac/gymlog/progress/ExerciseProgressScreen.kt`
- Create: `android/feature/progress/src/test/kotlin/com/chrisgoac/gymlog/progress/ProgressViewModelTest.kt`
- Create: `android/feature/progress/src/androidTest/kotlin/com/chrisgoac/gymlog/progress/ProgressAccessibilityTest.kt`

**Interfaces:** Screen state contains selected period/gym, summary cards, chart series, PRs, balance, stalls, and suggestions. Every chart has `contentDescription` plus a table/list alternative generated from the same points.

- [ ] Test filter changes, empty/partial data, deleted workouts, historical edit refresh, and intelligent-report Pro action state.
- [ ] Run tests; confirm failure.
- [ ] Implement Canvas charts with bounded axes, safe equal-value domains, visible focus, high contrast, and touch exploration semantics.
- [ ] Implement weekly summary and drill-down screens; leave report navigation wired to the entitlement interface consumed in Plan 5.
- [ ] Run unit, screenshot, TalkBack, and 200% font tests.
- [ ] Commit: `git add android/core/designsystem android/feature/progress && git commit -m "feat(android): add native progress insights"`.

### Task 4: Implement body measurements

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/body/BodyRepository.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/body/OfflineBodyRepository.kt`
- Create: `android/feature/body/src/main/kotlin/com/chrisgoac/gymlog/body/BodyScreen.kt`
- Create: `android/feature/body/src/main/kotlin/com/chrisgoac/gymlog/body/BodyEntryEditor.kt`
- Create: `android/feature/body/src/test/kotlin/com/chrisgoac/gymlog/body/BodyViewModelTest.kt`

**Interfaces:** Support weight plus predefined/custom metric keys, date, value, unit, note, tombstone, and ordered chart history. Repository mutations always enqueue sync.

- [ ] Test create/edit/delete, custom metric validation, unit display conversion, same-day multiple entries, chart ordering, and offline restart.
- [ ] Run tests; confirm failure.
- [ ] Implement repository, editor, metric selector, summary deltas, accessible chart, and history list.
- [ ] Route progress-photo actions to Plan 5's media capability.
- [ ] Run tests.
- [ ] Commit: `git add android/core/domain android/core/data android/feature/body && git commit -m "feat(android): add body tracking"`.

### Task 5: Port deterministic achievement reconciliation

**Files:**
- Create: `contracts/domain/achievement-cases.json`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/achievement/AchievementRules.kt`
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/achievement/ReconcileAchievementsUseCase.kt`
- Create: `android/feature/achievements/src/main/kotlin/com/chrisgoac/gymlog/achievements/AchievementsScreen.kt`
- Create: `android/core/domain/src/test/kotlin/com/chrisgoac/gymlog/domain/achievement/AchievementParityTest.kt`

**Interfaces:** Reconciliation returns `unlock`, `retain`, or `tombstone` per achievement evidence. Re-earned tombstoned achievements retain stable logical identity and update evidence idempotently.

- [ ] Export current `lib/logros*` and `lib/reconciliar-logros*` cases, adding history-edit removal/re-earn scenarios.
- [ ] Run Kotlin fixtures; confirm failure.
- [ ] Implement pure rules and a transactional reconciliation use case that produces an outbox mutation only when state changes.
- [ ] Implement streak, milestones, and PR screens with locked/unlocked semantics.
- [ ] Run TypeScript/Kotlin parity and UI tests.
- [ ] Commit: `git add contracts/domain android/core/domain android/feature/achievements && git commit -m "feat(android): reconcile achievements natively"`.

### Task 6: Implement gyms and synchronized user settings

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/chrisgoac/gymlog/domain/settings/SettingsRepository.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/settings/OfflineSettingsRepository.kt`
- Create: `android/feature/settings/src/main/kotlin/com/chrisgoac/gymlog/settings/GymsScreen.kt`
- Create: `android/feature/settings/src/main/kotlin/com/chrisgoac/gymlog/settings/TrainingSettingsScreen.kt`
- Create: `android/feature/settings/src/test/kotlin/com/chrisgoac/gymlog/settings/SettingsViewModelTest.kt`

**Interfaces:** Synced settings: units, theme, goals, progression rules, default gym, and routine order. Device-only DataStore: onboarding, last local filters, notification choice, device ID, and bootstrap metadata.

- [ ] Test gym CRUD/tombstone/in-use confirmation, default gym, routine ordering, synced setting convergence, and device-only setting exclusion from outbox.
- [ ] Run tests; confirm failure.
- [ ] Implement repositories and settings screens with validation and immediate theme/unit application.
- [ ] Preserve unknown future setting keys during sync but expose only known typed keys to features.
- [ ] Run tests.
- [ ] Commit: `git add android/core/domain android/core/data android/feature/settings && git commit -m "feat(android): add gyms and synchronized settings"`.

### Task 7: Add atomic backup export and import

**Files:**
- Create: `android/core/model/src/main/kotlin/com/chrisgoac/gymlog/model/backup/BackupV1.kt`
- Create: `android/core/data/src/main/kotlin/com/chrisgoac/gymlog/data/backup/BackupRepository.kt`
- Create: `android/core/data/src/test/kotlin/com/chrisgoac/gymlog/data/backup/BackupRepositoryTest.kt`
- Create: `android/feature/settings/src/main/kotlin/com/chrisgoac/gymlog/settings/BackupScreen.kt`

**Interfaces:** `BackupV1(schemaVersion, exportedAt, entities)` contains the 14 user-owned synchronized types but excludes server metadata that must be regenerated. Import modes are `Merge` and `ReplaceAfterConfirmation`; both validate first and assign current-user ownership internally.

- [ ] Port PWA backup fixtures; test round trip, corrupt/oversized/unknown-version file, foreign `userId` stripping, no secret fields, merge idempotency, and transaction rollback.
- [ ] Run tests; confirm failure.
- [ ] Implement streaming JSON read/write through Storage Access Framework URIs without broad storage permission.
- [ ] Make imported changes generate bounded logical outbox mutations and recompute derived state.
- [ ] Run tests and manual export/import on API 26 and 36.
- [ ] Commit: `git add android/core/model android/core/data android/feature/settings && git commit -m "feat(android): add safe backup import export"`.

### Task 8: Verify free non-media parity

**Files:**
- Create: `android/app/src/androidTest/kotlin/com/chrisgoac/gymlog/FreeParityE2eTest.kt`
- Create: `docs/verification/android-phase-04-insights-settings.md`
- Modify: `docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md`

- [ ] Run every shared domain fixture in both runtimes and attach a zero-diff result.
- [ ] Edit an old workout and prove progress, PRs, 1RM, volume, balance, streak, stalls, achievements, and Coach snapshot inputs all change consistently.
- [ ] Exercise body, gyms, units/theme, routine order, backup export/delete/import offline.
- [ ] Run API 26/36 instrumented tests, `testDebug`, `lintDebug`, and `assembleDebug`.
- [ ] Record accessibility evidence for chart alternatives and large fonts.
- [ ] Mark the phase gate and commit: `git add android/app/src/androidTest docs/verification docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md && git commit -m "test(android): verify insights and settings parity"`.
