# Android phase 1 (backend sync) verification

- Code under test: commit `12af079` (branch `feat/android-backend-sync`, HEAD before this document).
- Date: 2026-10-10.
- All database commands used explicit `127.0.0.1` disposable databases (`gymlog_sync_rehearsal`, `gymlog_sync_test`). `.env.local` (production) was never read or used.

> **Deployment note: migration 0001 must deploy to production together with the legacy push journaling (same release).**
> The migration backfills `sync_changes` once. After it, every legacy PWA write must also be journaled (`applyLegacyPush`, `app/api/sync/push`), otherwise v1 pulls never see PWA writes made after the backfill. Do not apply the migration alone, and do not ship the legacy journaling before the migration.

### Deploy runbook

1. **Migrate:** run `scripts/migrate-sync-foundation.mjs` against production (`MIGRATION_DATABASE_URL`).
2. **Deploy** the release that contains the legacy push journaling and the v1 routes.
3. **Re-run** `scripts/migrate-sync-foundation.mjs` once the new deployment is live. It is idempotent (run 2 below adds 0 rows when nothing changed) and journals any row the *old* code wrote between steps 1 and 2 — those rows have no `server_version` and would otherwise never reach a v1 pull.

## 1. Migration rehearsal

Pre-migration schema = `git show 753585a:db/schema.ts` pushed to an empty DB with `drizzle-kit push` (temp config outside tracked files, both `DATABASE_URL` and `DATABASE_URL_UNPOOLED` set on the command line). Two synthetic users (`user_A`, `user_B`) seeded with 3 rows per table each (2 live + 1 tombstone); `user_settings` uses the same id `ajuste-1` for both users.

```
DATABASE_URL=postgresql://postgres:***@127.0.0.1:5432/gymlog_sync_rehearsal \
DATABASE_URL_UNPOOLED=<same> npx drizzle-kit push --config <temp pre-schema config> --force
MIGRATION_DATABASE_URL=postgresql://postgres:***@127.0.0.1:5432/gymlog_sync_rehearsal \
  node scripts/migrate-sync-foundation.mjs      # run twice
```

| Table | Rows before | Tombstones | Rows after run 1 | Rows after run 2 |
|---|---|---|---|---|
| exercises, routines, routine_exercises, workout_sessions, logged_exercises, logged_sets, gyms, exercise_photos, user_settings, coach_messages, body_metrics, progress_photos, mesocycles, achievements (each) | 6 | 2 | 6 | 6 |
| **Total (14 tables)** | **84** | **28** | **84** | **84** |

| | `sync_changes` rows |
|---|---|
| Before migration | table absent (0) |
| After run 1 | 84 (+84; 6 per table = 3 per user, 42 per user, camelCase table names) |
| After run 2 | 84 (+0) — second run is a no-op |

- No domain row was lost or duplicated; every row has `server_version` (script asserts it inside the transaction); `server_version` equals its journal `sequence`; backfilled rows carry `last_device_id = legacy-pwa`.
- Both users' `ajuste-1` kept independent rows (server versions 25 and 26).

## 2. Scenarios against the rehearsal DB (post-migration)

Real service functions (`pushMutations`, `pullChanges`, `applyLegacyPush`, `lockUserSync`) through `getPoolDb()` with `DATABASE_URL` set to the rehearsal DB. The rehearsal script (throwaway vitest file, seed and helper scripts) is not committed: it lives in the SDD workspace, `.superpowers/sdd/2026-08-31-gymlog-android-01-backend-sync/rehearsal/` (git-ignored).

| Scenario | Result |
|---|---|
| Multi-page pull, interrupted after 2 pages of 5, resumed from saved cursor | 42 changes total for `user_A`, no duplicates, no gaps; union of interrupted+resumed equals single full pull; 14 tombstones emitted; 0 foreign rows |
| Duplicate push replay (same `mutationId`) | Identical response; journal +1 only; 1 `sync_mutations` row |
| Stale `baseServerVersion` | Update applied (LWW), conflict reported with reason `STALE_BASE` |
| Tombstone push (`baseServerVersion: null` on an existing row) | Delete applied **and** reported as `STALE_BASE` (expected: a null base on an existing row is stale); tombstone visible in the next pull |
| Cross-user (push of other user's record id) | HTTP 409 `RECORD_ID_CONFLICT`; other user's row unchanged; legacy push of foreign id silently dropped; same `userSettings` id stays independent per user; neither user's pull contains the other's rows |
| Legacy PWA push | Record visible in the next v1 pull (exactly 1 change) |

## 3. Phase gate commands

| Command | Result |
|---|---|
| `SYNC_TEST_DATABASE_URL=postgresql://postgres:***@127.0.0.1:5432/gymlog_sync_test DATABASE_URL=<same> DATABASE_URL_UNPOOLED=<same> npx vitest run` | 86 files, 537 tests passed (integration suites enabled) |
| `npx vitest run app/api/sync lib/sync` (legacy PWA sync, no DB) | 7 files, 54 passed, 5 DB-gated skipped (run in the full suite above) |
| `npm run lint` | clean |
| `npm run build` (`DATABASE_URL` unset) | succeeded |

Gate verdict: PWA build and legacy sync tests pass; backend phase gate marked complete in the roadmap.
