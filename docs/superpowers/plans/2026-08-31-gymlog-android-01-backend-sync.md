# GymLog Android Backend Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a backward-compatible, authenticated, validated, idempotent `/api/v1/sync` API whose cloud schema preserves every field required by Android and whose paginated cursor is safe across all synchronized tables.

**Architecture:** Add strict Zod contracts and service functions below thin Next.js route handlers. Record every accepted row change in a user-scoped global `sync_changes` stream, and record every logical mutation in `sync_mutations`; both are written in the same Postgres transaction as domain rows. Keep legacy `/api/sync/*` routes during coexistence, adapting them to shared merge rules where practical.

**Tech Stack:** Next.js 16 route handlers, TypeScript, Zod, Clerk server auth, Drizzle ORM/Postgres, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-31-gymlog-android-nativo-design.md`

## Global Constraints

- Read `node_modules/next/dist/docs/01-app/01-getting-started/15-route-handlers.md` and `node_modules/next/dist/docs/01-app/03-api-reference/03-file-conventions/proxy.md` before editing routes.
- Every route handler calls Clerk `auth()` itself; Proxy is never the authorization boundary.
- Never accept `userId`, `serverVersion`, or entitlement state as writable client fields.
- `sync_changes.sequence` is the only pull ordering source. Timestamps do not advance cursors.
- A push mutation containing multiple operations commits all operations and its dedupe row or none.
- Unknown tables, unknown fields, wrong scalar types, invalid references, oversized batches, and future timestamps return stable 4xx errors.
- Shared fixtures under `contracts/v1/fixtures/` are production contracts and may not contain secrets or real account data.

---

### Task 1: Install validation support and freeze the v1 envelope

**Files:**
- Modify: `package.json`
- Modify: `package-lock.json`
- Create: `lib/api/v1/contracts/common.ts`
- Create: `lib/api/v1/contracts/sync.ts`
- Create: `lib/api/v1/contracts/sync.test.ts`
- Create: `contracts/v1/fixtures/push-valid.json`
- Create: `contracts/v1/fixtures/push-invalid-server-field.json`
- Create: `contracts/v1/fixtures/pull-page.json`

**Interfaces:** Export `SyncTableNameSchema`, `PushRequestSchema`, `PushResponseSchema`, `PullResponseSchema`, `ApiErrorBodySchema`, and inferred DTO types matching the roadmap exactly. Enforce `1..50` mutations/request, `1..200` operations/mutation, `deviceId` and UUID-format IDs, safe millisecond timestamps, and `.strict()` objects.

- [ ] Write `sync.test.ts` to parse `push-valid.json`, reject `userId`/`serverVersion` from the invalid fixture, reject server-only table names, and round-trip `pull-page.json`.
- [ ] Run `npx vitest run lib/api/v1/contracts/sync.test.ts`; confirm failure because schemas are absent.
- [ ] Run `npm install zod @clerk/nextjs@^7.5.2` and inspect the lockfile diff.
- [ ] Implement the strict schemas with per-table record unions, including `descripcion`, `execution`, `incrementoKg`, and `repsObjetivoMin`.
- [ ] Run the focused test and `npm run lint`; confirm both pass.
- [ ] Commit: `git add package.json package-lock.json lib/api/v1/contracts contracts/v1/fixtures && git commit -m "feat(api): define mobile sync v1 contracts"`.

### Task 2: Make cloud schema parity explicit

**Files:**
- Modify: `db/schema.ts`
- Create: `db/schema-sync.test.ts`
- Create: `drizzle.config.ts`
- Modify: `package.json`
- Create: `drizzle/0001_android_sync_foundation.sql`
- Create: `scripts/migrate-sync-foundation.mjs`

**Interfaces:** Add missing exercise/routine fields; add `serverVersion: bigint`, `lastDeviceId`, and `lastMutationId` server-only metadata to the 14 synchronized tables; create `sync_mutations(userId, mutationId, responseJson, processedAt)` with composite primary key; create `sync_changes(sequence bigserial, userId, tableName, recordId, changedAt)` with index `(userId, sequence)`; create `api_rate_buckets(scope, subjectHash, windowStart, count)` for shared server-side limits. `serverVersion` is populated from the inserted `sync_changes.sequence` in the same transaction.

- [ ] Write schema tests using Drizzle table metadata to assert all 14 tables have `userId`, `updatedAt`, `deletedAt`, and `serverVersion`, and assert the four drift fields use the agreed names/types.
- [ ] Run `npx vitest run db/schema-sync.test.ts`; confirm the missing columns fail.
- [ ] Update `db/schema.ts`, add Drizzle generation/migration scripts, and generate the reviewed SQL migration.
- [ ] Make the SQL additive and backward compatible: nullable/defaulted columns first, indexes after columns, no destructive rename/drop.
- [ ] Backfill every existing live row and tombstone into `sync_changes`, assign its resulting sequence to `serverVersion`, and set the legacy writer tuple so a first Android bootstrap cannot omit pre-migration data.
- [ ] Implement `scripts/migrate-sync-foundation.mjs` with an advisory lock and transaction, matching existing environment loading conventions.
- [ ] Run `npx vitest run db/schema-sync.test.ts`, `npm run lint`, and `npm run build`.
- [ ] Apply the migration only to a disposable/test database, then query the four drift fields and two sync tables; do not run it against production in this task.
- [ ] Commit: `git add db drizzle.config.ts drizzle scripts package.json package-lock.json && git commit -m "feat(db): add mobile sync metadata and schema parity"`.

### Task 3: Add stable auth and error boundaries

**Files:**
- Create: `lib/api/v1/auth.ts`
- Create: `lib/api/v1/errors.ts`
- Create: `lib/api/v1/response.ts`
- Create: `lib/api/v1/route.ts`
- Create: `lib/api/v1/route.test.ts`
- Create: `lib/api/v1/rate-limit.ts`
- Create: `lib/api/v1/rate-limit.test.ts`

**Interfaces:**

```ts
export interface ApiPrincipal { userId: string }
export async function requirePrincipal(): Promise<ApiPrincipal>
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string,
    message: string, readonly retryable: boolean,
    readonly details?: Record<string, string[]>)
}
export function withApiRoute(
  handler: (request: Request, principal: ApiPrincipal) => Promise<Response>
): (request: Request) => Promise<Response>
export function consumeRateLimit(
  scope: string, subject: string, policy: RateLimitPolicy
): Promise<RateLimitDecision>
```

- [ ] Test missing/invalid Clerk sessions as `401 AUTH_REQUIRED`, Zod failures as `400 INVALID_REQUEST`, unexpected failures as non-leaky `500 INTERNAL_ERROR`, and `ApiError` serialization.
- [ ] Test concurrent bucket increments, window rollover, hashed subjects, configurable sync/AI/media policies, and `429 RATE_LIMITED` with retry metadata.
- [ ] Run `npx vitest run lib/api/v1/route.test.ts`; confirm module-not-found failure.
- [ ] Implement `requirePrincipal()` with `auth()` from `@clerk/nextjs/server` and a test-injectable auth seam.
- [ ] Implement `withApiRoute()` so every response uses `application/json`, a request ID, and roadmap `ApiErrorBody`; implement the transactional PostgreSQL limiter without storing raw IP/token values.
- [ ] Run the focused test and lint.
- [ ] Commit: `git add lib/api/v1 && git commit -m "feat(api): add authenticated v1 route boundary"`.

### Task 4: Implement deterministic merge and idempotent push service

**Files:**
- Modify: `lib/sync/server-merge.ts`
- Modify: `lib/sync/server-merge.test.ts`
- Create: `lib/api/v1/sync/tables.ts`
- Create: `lib/api/v1/sync/push.ts`
- Create: `lib/api/v1/sync/push.test.ts`
- Create: `contracts/v1/fixtures/conflict-cases.json`

**Interfaces:**

```ts
export interface VersionTuple {
  updatedAt: number;
  deviceId: string;
  mutationId: string;
}
export function compareVersionTuple(a: VersionTuple, b: VersionTuple): -1 | 0 | 1
export async function pushMutations(
  tx: DbTransaction,
  principal: ApiPrincipal,
  request: PushRequest
): Promise<PushResponse>
```

`tables.ts` is an exhaustive registry for only the 14 client-writable tables. Each adapter validates ownership, maps DTO fields explicitly, and returns the current row snapshot. No dynamic SQL table name comes from input.

- [ ] Add fixtures for equal timestamps, device tie-break, mutation tie-break, stale `baseServerVersion`, tombstone wins, duplicate `mutationId`, cross-user record ID, and a timestamp over five minutes in the future.
- [ ] Write tests that require one transaction per logical mutation and prove a failing second operation rolls back the first and does not insert `sync_mutations`.
- [ ] Run `npx vitest run lib/sync/server-merge.test.ts lib/api/v1/sync/push.test.ts`; confirm new cases fail.
- [ ] Implement tuple comparison, future-clock normalization, table adapters, dedupe lookup, row lock/update, `sync_changes` insert, `serverVersion` update, and stored replay response.
- [ ] Return conflicts for stale revisions while applying the deterministic winner; never reveal another user's row.
- [ ] Run focused tests twice to prove duplicate delivery is idempotent.
- [ ] Commit: `git add lib/sync/server-merge* lib/api/v1/sync contracts/v1/fixtures/conflict-cases.json && git commit -m "feat(sync): add transactional idempotent push"`.

### Task 5: Implement global cursor pull and resumable pagination

**Files:**
- Create: `lib/api/v1/sync/cursor.ts`
- Create: `lib/api/v1/sync/cursor.test.ts`
- Create: `lib/api/v1/sync/pull.ts`
- Create: `lib/api/v1/sync/pull.test.ts`
- Create: `contracts/v1/fixtures/pull-pagination-cases.json`

**Interfaces:**

```ts
export function encodeCursor(sequence: bigint): string
export function decodeCursor(cursor: string | null): bigint
export async function pullChanges(
  tx: DbTransaction,
  principal: ApiPrincipal,
  cursor: string | null,
  limit: number
): Promise<PullResponse>
```

- [ ] Test cursor round trips at `0`, `2^53`, and PostgreSQL bigint maximum; reject malformed/base64 overflow cursors with `INVALID_CURSOR`.
- [ ] Test an interleaved 1,001-change stream across all table types with page size 500, no gaps/duplicates, tombstones included, and cross-user changes absent.
- [ ] Run the focused tests; confirm failure.
- [ ] Implement base64url cursor encoding without converting bigint to JavaScript `number`.
- [ ] Query `sync_changes` by `(userId, sequence)`, fetch explicit snapshots through the table registry, serialize `serverVersion` as decimal text, and set cursor to the last fully returned sequence.
- [ ] Run focused tests and verify an empty page returns the input cursor with `hasMore: false`.
- [ ] Commit: `git add lib/api/v1/sync contracts/v1/fixtures/pull-pagination-cases.json && git commit -m "feat(sync): add monotonic paginated pull"`.

### Task 6: Expose thin `/api/v1/sync` route handlers

**Files:**
- Create: `app/api/v1/sync/push/route.ts`
- Create: `app/api/v1/sync/push/route.test.ts`
- Create: `app/api/v1/sync/pull/route.ts`
- Create: `app/api/v1/sync/pull/route.test.ts`
- Modify: `app/api/sync/push/route.ts`
- Modify: `app/api/sync/push/route.test.ts`
- Modify: `app/api/sync/pull/route.test.ts`

**Interfaces:** `POST /api/v1/sync/push` parses `PushRequestSchema`; `GET /api/v1/sync/pull?cursor=&limit=` defaults to 500 and caps at 500. Both run through `withApiRoute`; neither accepts cookies as the only mobile credential assumption. The legacy PWA push route keeps its existing request/response shape but delegates accepted writes to a compatibility adapter that also writes `sync_changes` and `serverVersion`.

- [ ] Test request validation, bearer auth, rate-limit seam, 413 body limit, content type, service delegation, and stable JSON errors.
- [ ] Run `npx vitest run app/api/v1/sync`; confirm failure.
- [ ] Implement v1 route handlers as parsing/transaction/delegation only.
- [ ] Adapt legacy push writes to the shared journal with a server-generated mutation ID and `legacy-pwa` device ID, then add regression tests showing existing PWA shapes still work and appear in the next v1 pull page.
- [ ] Run `npx vitest run app/api/v1/sync app/api/sync` and lint.
- [ ] Commit: `git add app/api/v1/sync app/api/sync && git commit -m "feat(api): expose mobile sync v1 routes"`.

### Task 7: Prove cross-language contract readiness

**Files:**
- Create: `contracts/v1/README.md`
- Create: `contracts/v1/fixtures/bootstrap-all-entities.json`
- Create: `lib/api/v1/contracts/fixtures.test.ts`
- Modify: `README.md`

**Interfaces:** `bootstrap-all-entities.json` contains one live row and one tombstone for each synchronized table using synthetic IDs. `contracts/v1/README.md` documents timestamp units, decimal `serverVersion`, cursor opacity, limits, retry semantics, and the rule that accepted mutations are replayable verbatim.

- [ ] Write a fixture test that validates every request/response, asserts all 14 table names occur, and rejects server-only entity names.
- [ ] Run the test and observe failure until the aggregate fixture is complete.
- [ ] Add the sanitized aggregate fixture and contract documentation.
- [ ] Run `npx vitest run lib/api/v1 db lib/sync app/api/v1 app/api/sync`.
- [ ] Run `npm run lint`, `npm run build`, and `git diff --check`.
- [ ] Commit: `git add contracts lib/api/v1 README.md && git commit -m "test(api): freeze Android sync contract fixtures"`.

### Task 8: Migration rehearsal and phase evidence

**Files:**
- Create: `docs/verification/android-phase-01-backend.md`
- Modify: `docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md`

- [ ] Create a disposable database from the pre-migration schema and seed two synthetic users with all entity types.
- [ ] Run the migration script twice; assert the second run is harmless.
- [ ] Run duplicate push, interrupted multi-page pull, stale conflict, tombstone, and cross-user isolation cases against the disposable database.
- [ ] Record commands, commit SHA, before/after row counts, and sanitized results in the verification document.
- [ ] Mark the backend phase gate complete only if the PWA build and legacy sync tests still pass.
- [ ] Commit: `git add docs/verification docs/superpowers/plans/2026-08-31-gymlog-android-native-roadmap.md && git commit -m "docs: record Android backend phase verification"`.
