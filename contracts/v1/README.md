# GymLog sync contract v1

Frozen wire contract between the Next.js backend and the native Android client. The schemas live in
`lib/api/v1/contracts/{common,sync}.ts`; the JSON files in `fixtures/` are the shared test vectors
(the JVM tests of the Android client load them verbatim; `lib/api/v1/contracts/fixtures.test.ts`
validates them on the server side).

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/sync/push` | Send local mutations |
| GET | `/api/v1/sync/pull?cursor=<opaque>&limit=<n>` | Incremental download |

Both responses carry `cache-control: no-store`.

## Authentication and request id

- `Authorization: Bearer <Clerk session token>`. A missing/invalid token gives `401 AUTH_REQUIRED`.
- `X-Request-Id` (optional): if it matches `^[A-Za-z0-9._-]{8,128}$` the server echoes it back,
  otherwise it generates a UUID. Every response (success and error) has an `x-request-id` header.
  Log it for support.

## Data model

- 14 synchronized tables, exactly: `exercises`, `routines`, `routineExercises`, `workoutSessions`,
  `loggedExercises`, `loggedSets`, `gyms`, `exercisePhotos`, `userSettings`, `coachMessages`,
  `bodyMetrics`, `progressPhotos`, `mesocycles`, `achievements`. Any other table name is rejected.
- Records are strict objects (unknown fields are rejected): they never contain `userId`,
  `serverVersion` or `serverUpdatedAt`. Every record has `id`, `updatedAt`, `deletedAt`
  (`null` = live; a number = tombstone). Deletes are tombstones, never physical.
- Record `id`: 1..128 chars, `^[A-Za-z0-9._:-]+$`. `mutationId`: UUID. `deviceId`: 1..128 chars.
- Optional columns are `nullish`: the client may omit them or send `null`; the server always
  emits them as `null` in pull responses.
- **Full-record replace.** Every applied write replaces the whole stored record: an omitted
  nullable field is stored as `null`, not left unchanged. Clients must always send complete
  records. Adding a field to a record is a contract change and requires a contract version bump.
- Integer columns (`orden`, `seriesObjetivo`, `semanas`, `reps`, …) are int32:
  `-2147483648..2147483647`. Values outside that range give `400 INVALID_REQUEST`.
- No string field (record fields, `deviceId`) and no string or key inside a JSON value
  (`mesocycles.progresion`) may contain U+0000; it gives `400 INVALID_REQUEST`.
- **Never push catalog exercises** (`seed-*` ids). They share ids across users, so the first user
  to write one owns it and every other user gets a permanent `409 RECORD_ID_CONFLICT`. Only push
  records the user created.
- **References are not enforced.** `INVALID_REFERENCE` is returned only when a record references
  an id owned by another user; references to ids the server does not know yet are accepted. The
  client must therefore tolerate dangling references (e.g. a `loggedSets` row whose
  `loggedExerciseId` has not arrived yet): do not declare Room foreign-key constraints between
  synced tables.

### Timestamps

All timestamps (`updatedAt`, `deletedAt`, `fecha`, `createdAt`, `fechaInicio`, `fechaDesbloqueo`)
are integer **epoch milliseconds** in `0..Number.MAX_SAFE_INTEGER` (2^53 - 1). Floats are rejected.

**Clock.** The device clock may be wrong. The client derives its offset to the server from the
HTTP `Date` header of every response (`offset = serverDate - localNowAtResponse`) and stamps
`updatedAt`/`deletedAt` with `localNow + offset`. The server tolerates 5 minutes in the future
(see Limits); anything later rejects the whole request with `FUTURE_TIMESTAMP`.

### serverVersion

PostgreSQL `bigint` serialized as a **base-10 decimal string** (`^(0|[1-9][0-9]{0,18})$`), never a
JSON number. Parse it as a 64-bit integer (Kotlin `Long`). It is the journal sequence of the row's
last accepted write. `baseServerVersion` in push operations is the last `serverVersion` the client
saw for that record, or `null` if it never saw one. A stored legacy row that has no version yet is
reported as `"0"` in conflicts.

### Cursor

Opaque to clients: store it and send it back unchanged. (Internally it is base64url, no padding,
of the decimal text of the journal sequence; only the server decodes it.) Omitted = from the
beginning. An empty `cursor=` is also treated as the beginning, but on an empty page it is echoed
back as `""`: clients send no `cursor` parameter instead of an empty one. Malformed, negative, non-canonical or > 2^63-1 gives `400 INVALID_CURSOR`.
The response `cursor` is the last journal entry examined, even if the page has fewer changes than
`limit` (superseded entries are skipped but advance the cursor). On an empty page the request
cursor is returned (or the encoding of `0` if none was sent). Persist the new cursor only after
the page has been applied locally. Keep pulling while `hasMore` is `true`.

## Limits

| Limit | Value |
|-------|-------|
| Mutations per push request | 1..50, unique `mutationId`s (duplicates give `400 INVALID_REQUEST`) |
| Operations per mutation | 1..50, unique `(table, record.id)` (duplicates give `400 INVALID_REQUEST`) |
| Recommended batch size | at most 20 mutations and 50 operations per request |
| Push body size (`MAX_PUSH_BODY_BYTES`) | 2 MiB (2 * 1024 * 1024); larger gives `413 PAYLOAD_TOO_LARGE` |
| Pull `limit` | default 500, maximum 500. Integers above 500 (up to 2^53 - 1) are clamped to 500; `< 1`, non-integer or not a safe integer gives `400 INVALID_REQUEST` |
| Rate limit (`sync` policy, per user, push and pull share it) | 120 requests per fixed 60 s window |
| Future clock tolerance (`FUTURE_CLOCK_TOLERANCE_MS`) | 300000 ms (5 min) |

Future clock: if any record in a push has `updatedAt` or non-null `deletedAt` greater than
server `now + 5 min`, the **whole request** is rejected with `400 FUTURE_TIMESTAMP`
(`details.mutationId` names the mutation) and nothing is written.

Push requires `Content-Type: application/json` (`415 UNSUPPORTED_MEDIA_TYPE` otherwise) and a
UTF-8 body: invalid UTF-8 gives `400 INVALID_JSON`.

## Push semantics

Response: `{ "acceptedMutationIds": [...], "conflicts": [...] }`.

- Each mutation is atomic (all its operations commit together or not at all) and is deduplicated by
  `mutationId` per user. Mutations are processed in request order. If one fails with an error the
  whole request returns that error (`details.mutationId` identifies it); mutations processed before
  it in the same request are rolled back too, because the request is a single transaction.
- **Accepted mutations are replayable verbatim.** Re-sending an already accepted mutation (same
  `mutationId`, same payload) returns it in `acceptedMutationIds` again with the same stored
  conflicts, without writing anything. Clients must retry the exact same bytes/ids after timeouts
  or retryable errors, and may drop a local mutation only once its id appears in
  `acceptedMutationIds`.
- **Replay is keyed by `mutationId` only.** A known `mutationId` returns its stored response even
  if the body now differs: the new payload is ignored. A changed write needs a new `mutationId`.
- A conflict is informational: it never rejects the mutation. `acceptedMutationIds` includes
  mutations whose operations produced conflicts.

### Last-write-wins version tuple

The incoming write for a record is compared with the stored row by the tuple
`(updatedAt, deviceId, mutationId)`, lexicographically:

1. `updatedAt` numerically;
2. `deviceId` as a string, by **UTF-16 code units** (Kotlin `String.compareTo`, JS `<`; never
   locale-aware);
3. `mutationId` as a string, same ordering.

The stored `deviceId`/`mutationId` of a legacy write that lacks them compare as `""`.
Incoming greater than stored: applied. Equal: the same mutation already applied, no-op, no
conflict. Incoming smaller: not applied. A tombstone is an ordinary record under this rule: it
wins only with a greater tuple, and a live record does not resurrect a tombstone unless its tuple
is greater. Reference vectors: `fixtures/conflict-cases.json`.

### Conflict reasons

`conflicts[]` entries: `{ mutationId, table, recordId, serverVersion, reason }`. `serverVersion` is
the stored version after the decision (the new version if the write applied; the existing one if it
lost).

| reason | Meaning |
|--------|---------|
| `STALE_BASE` | The write applied (it won), but `baseServerVersion` did not match the stored `serverVersion` (null while the row exists, non-null while it does not, or different). |
| `SUPERSEDED` | The write lost the tuple comparison and was not applied; the client should adopt the server record on its next pull. |

## Pull semantics

Response: `{ "changes": [{ table, serverVersion, record }], "cursor": "...", "hasMore": bool }`.
A record emits one change, carrying its latest state (tombstones included); older journal entries
of the same record are skipped. `changes` are in increasing `serverVersion` order.
There is **no parent-before-child ordering**, neither within a page nor across pages: a child
(e.g. `loggedSets`) may arrive before its parent. Apply every change regardless of whether its
references resolve yet (see "References are not enforced"). A bootstrap
download is a pull from an empty cursor (`fixtures/bootstrap-all-entities.json`).

## Errors

Body: `{ "error": { "code", "message", "retryable", "details"? } }`; `details` maps a field to a list
of messages. Retry only when `retryable` is `true`, with backoff.

| HTTP | code | retryable | Cause |
|------|------|-----------|-------|
| 400 | `INVALID_REQUEST` | false | Schema validation failed (`details` lists fields; also bad `limit`) |
| 400 | `INVALID_JSON` | false | Body is not valid JSON |
| 400 | `INVALID_CURSOR` | false | Malformed cursor |
| 400 | `INVALID_REFERENCE` | false | Record references an id owned by another user |
| 400 | `FUTURE_TIMESTAMP` | false | Timestamp beyond now + 5 min |
| 400 | `UNKNOWN_TABLE` | false | Table without server adapter (defensive; schema rejects first) |
| 401 | `AUTH_REQUIRED` | false | Missing or invalid Bearer token |
| 409 | `RECORD_ID_CONFLICT` | false | Record id is held by another user |
| 409 | `MUTATION_IN_FLIGHT` | true | Same `mutationId` still being processed by a concurrent request |
| 409 | `WRITE_RACE` | true | Concurrent write on the same record; retry |
| 413 | `PAYLOAD_TOO_LARGE` | false | Push body over 2 MiB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | false | Content-Type is not `application/json` |
| 429 | `RATE_LIMITED` | true | Rate limit exceeded |
| 500 | `INTERNAL_ERROR` | true | Unhandled server error |
| 503 | `RETRY_LATER` | true | PostgreSQL deadlock/serialization failure; retry as is |

### Retry-After

`429 RATE_LIMITED` sets the `retry-after` header to a positive integer number of seconds (time until
the current fixed window ends; at least 1) and repeats it in `error.details.retryAfterSeconds[0]`.
Wait at least that long. Other retryable errors have no `Retry-After`: use exponential backoff
with jitter and resend the identical request (safe thanks to mutation replay).

### Quarantine of poison mutations

A non-retryable error that names a mutation (`details.mutationId`) fails the whole request, so
resending the same batch would fail forever. This applies to `RECORD_ID_CONFLICT`,
`INVALID_REFERENCE`, `FUTURE_TIMESTAMP` and `INVALID_REQUEST`. The client then:

1. removes that mutation from the outbox batch;
2. keeps it locally, flagged for the user (it was not synced);
3. resends the remaining mutations (they were rolled back and are safe to resend as is).

`INVALID_REQUEST` from schema validation reports field paths (`details.mutations`), not
`details.mutationId`; validate each mutation locally with these rules before enqueueing it, so a
schema error points at a client bug rather than at one specific queued mutation.

## Fixtures

| File | Content |
|------|---------|
| `push-valid.json` | Valid `PushRequest` |
| `push-invalid-server-field.json` | Must be rejected (record carries `userId`/`serverVersion`) |
| `pull-page.json` | `PullResponse` page with `hasMore: true` |
| `bootstrap-all-entities.json` | `PullResponse`: a live row and a tombstone for each of the 14 tables (28 changes, `hasMore: false`) |
| `conflict-cases.json` | LWW merge and push vectors |
| `pull-pagination-cases.json` | Cursor round-trips, invalid cursors, page-size expectations |

All IDs and text in fixtures are synthetic.

## AI (Pro)

Schemas: `lib/api/v1/ai/contracts.ts`. Routes live under `/api/v1/ai/*` (Coach, mesocycles,
intelligent reports, `POST /api/v1/ai/responses/:id/report`). The legacy PWA routes
`/api/coach` and `/api/coach/mesociclo` are not part of v1 and are not Pro-gated.

### Authorization

Every `/api/v1/ai/*` route authenticates the Bearer session (`withApiRoute`) and then calls
`requirePro` (`lib/api/v1/ai/guard.ts`). Pro is decided only on the server from
`subscription_entitlements` (`lib/entitlements/reader.ts`): a user is Pro iff one of their rows has
`state` `ACTIVE` or `GRACE` **and** `expires_at > now`. `ON_HOLD`, `PAUSED`, `CANCELED`, `EXPIRED`,
`REVOKED` and a missing row are not Pro. A canceled purchase with paid time left stays `ACTIVE`
(`auto_renewing = false`) until it expires. The client never sends entitlement state; Android's
own gating is presentation only.

| HTTP | code | retryable | Cause |
|------|------|-----------|-------|
| 403 | `PRO_REQUIRED` | false | No active Pro entitlement |
| 503 | `ENTITLEMENT_UNAVAILABLE` | true | Entitlement store unreachable; the request is refused, never granted (fail closed) |
| 404 | `RESPONSE_NOT_FOUND` | false | Reported response does not exist or belongs to another user (indistinguishable) |
| 503 | `AI_UNAVAILABLE` | true | The AI provider is not configured on the server |

### Server-only tables

`subscription_entitlements`, `ai_responses`, `ai_reports` and `reported_ai_responses`
(`drizzle/0003_ai_and_entitlements.sql`, applied with `npm run db:migrate:ai`) are written only by
the server. They are not sync tables: a push naming any of them fails with `400 INVALID_REQUEST`.

### Limits

- Coach `text`: 1..2000 chars, not blank. `clientMessageId`: UUID (retries reuse it). The client
  sends no history: the server builds thread context from the user's own synced `coachMessages`
  (newest live user/assistant turns within a char budget). `text` + serialized `snapshot` ≤ 36 KiB.
- Coach `snapshot`: strict allowlist of `CoachSnapshot` (`lib/coach-snapshot.ts`): stalled ≤ 5,
  muscle groups ≤ 12, measurements ≤ 6, weekly PRs ≤ 100; no photos, ids, URLs or object keys;
  ≤ 32 KiB as UTF-8 JSON.
- Mesocycle: `clientRequestId` UUID; `objetivo` ∈ {hipertrofia, fuerza, general}, 2..6 days/week,
  4..8 weeks, 30..90 min/session; catalog ≤ 400 entries with known muscle group/equipment.
- Response report: `category` ∈ {inaccurate, harmful, medical, offensive, other}, optional
  `context` ≤ 500 chars. A repeated report of the same response is idempotent.
- Stream events (one JSON object each): `start{clientMessageId, responseId}`, `delta{text ≤ 8000 chars}`,
  `complete{responseId, disclaimer: true}`, `error{code ^[A-Z_]{1,64}$, retryable}`.

### Coach (`POST /api/v1/ai/coach`)

Order: Bearer session → `ai` rate limit (429 + `Retry-After`) → `requirePro` → `503 AI_UNAVAILABLE`
(retryable) if DeepSeek is not configured → JSON body (`application/json`, ≤ 64 KiB, else 415/413)
validated by `CoachRequestSchema` (400 `INVALID_REQUEST`). All of these are ordinary JSON errors.
Then the response is `200 application/x-ndjson`: one event per line, `start` first, then `delta`s,
ending in exactly one `complete` or `error`. Concatenated `delta`s are the answer.

- `responseId` is the same for every attempt of one `clientMessageId` (`ai_responses` holds one row
  per user and `clientMessageId`). Store the assistant `coachMessages` row under `responseId`.
- Retry with the same `clientMessageId` after any failure. A complete response is replayed
  (start/delta/complete) without calling the model; an incomplete one is regenerated under the same
  `responseId`. The server never writes the user's message: it is only the client's synced
  `coachMessages` row (id = `clientMessageId`), so a retry cannot duplicate it.
- Context: the newest 20 live user/assistant turns (≤ 16 000 chars) of the user's synced
  `coachMessages`, excluding `clientMessageId`. Assistant turns count only if their id is a complete
  `ai_responses` row of that user, and the text used is the server-stored one (`ai_responses.content`):
  forged or edited synced assistant rows never reach the model.
- `complete.disclaimer: true`: the answer is general training guidance, not medical advice; show the
  disclaimer with it. The system prompt also forbids diagnoses/treatments.
- Stream error codes (all `retryable: true`): `AI_TIMEOUT` (model cut at 45 s), `AI_PROVIDER_ERROR`,
  `AI_EMPTY_RESPONSE`, `AI_RESPONSE_CONFLICT` (a concurrent attempt of the same `clientMessageId`
  completed first; retrying replays it), `INTERNAL_ERROR`. Partial deltas before an error are not a
  response. A client disconnect stops the model; the response stays incomplete.

### Report (`POST /api/v1/ai/responses/:id/report`)

Any signed-in user may report a response they own (no Pro needed); `ai` rate limit. Body
`{category, context?}`. `201 {responseId, created: true}` on the first report, `200 {…, created:
false}` on a repeat (the first report is kept). Unknown, malformed or another user's id: `404
RESPONSE_NOT_FOUND`.

### Test entitlement (non-production only)

There is no production bypass. To exercise the AI routes locally, seed a test entitlement into a
**local** database (the script refuses any host other than `localhost`/`127.0.0.1`/`::1` and never
reads `.env.local`):

```bash
MIGRATION_DATABASE_URL=postgresql://postgres:teacher123@127.0.0.1:5432/gymlog_sync_test \
  npm run ai:seed-test-entitlement -- --user <clerkUserId> [--days 30] [--state ACTIVE]
```

`--days` is an integer 1..365. Re-running renews the same row; `--state REVOKED` (or `EXPIRED`, …)
takes Pro away again. Rows with `source = 'test'` never grant Pro in a production build
(`NODE_ENV=production`, i.e. every Vercel deployment and `next start`): use `next dev` locally.
