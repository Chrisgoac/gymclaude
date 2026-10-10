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

### Timestamps

All timestamps (`updatedAt`, `deletedAt`, `fecha`, `createdAt`, `fechaInicio`, `fechaDesbloqueo`)
are integer **epoch milliseconds** in `0..Number.MAX_SAFE_INTEGER` (2^53 - 1). Floats are rejected.

### serverVersion

PostgreSQL `bigint` serialized as a **base-10 decimal string** (`^(0|[1-9][0-9]{0,18})$`), never a
JSON number. Parse it as a 64-bit integer (Kotlin `Long`). It is the journal sequence of the row's
last accepted write. `baseServerVersion` in push operations is the last `serverVersion` the client
saw for that record, or `null` if it never saw one. A stored legacy row that has no version yet is
reported as `"0"` in conflicts.

### Cursor

Opaque to clients: store it and send it back unchanged. (Internally it is base64url, no padding,
of the decimal text of the journal sequence; only the server decodes it.) Omitted or empty =
from the beginning. Malformed, negative, non-canonical or > 2^63-1 gives `400 INVALID_CURSOR`.
The response `cursor` is the last journal entry examined, even if the page has fewer changes than
`limit` (superseded entries are skipped but advance the cursor). On an empty page the request
cursor is returned (or the encoding of `0` if none was sent). Persist the new cursor only after
the page has been applied locally. Keep pulling while `hasMore` is `true`.

## Limits

| Limit | Value |
|-------|-------|
| Mutations per push request | 1..50 |
| Operations per mutation | 1..200 |
| Push body size (`MAX_PUSH_BODY_BYTES`) | 2 MiB (2 * 1024 * 1024); larger gives `413 PAYLOAD_TOO_LARGE` |
| Pull `limit` | default 500, maximum 500; larger values are clamped, `< 1` or non-integer gives `400 INVALID_REQUEST` |
| Rate limit (`sync` policy, per user, push and pull share it) | 120 requests per fixed 60 s window |
| Future clock tolerance (`FUTURE_CLOCK_TOLERANCE_MS`) | 300000 ms (5 min) |

Future clock: if any record in a push has `updatedAt` or non-null `deletedAt` greater than
server `now + 5 min`, the **whole request** is rejected with `400 FUTURE_TIMESTAMP`
(`details.mutationId` names the mutation) and nothing is written.

Push requires `Content-Type: application/json` (`415 UNSUPPORTED_MEDIA_TYPE` otherwise).

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
of the same record are skipped. `changes` are in increasing `serverVersion` order. A bootstrap
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
