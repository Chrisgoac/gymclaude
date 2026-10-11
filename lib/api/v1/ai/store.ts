import { sql } from 'drizzle-orm';
import type { PoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import type { ReportResponseRequest } from './contracts';

type Db = Pick<PoolDb, 'execute'>;

export const responseNotFound = () => new ApiError(404, 'RESPONSE_NOT_FOUND', 'AI response not found', false);

/**
 * Records the user's report of one of THEIR AI responses. Idempotent: a repeated report keeps the
 * first one (`created: false`). Another user's response and an unknown id both give the same 404,
 * so the endpoint is not an existence oracle.
 */
export async function reportAiResponse(
  db: Db,
  userId: string,
  responseId: string,
  input: ReportResponseRequest,
  now: number,
): Promise<{ created: boolean }> {
  const inserted = await db.execute(sql`
    INSERT INTO reported_ai_responses (id, user_id, response_id, category, context, created_at)
    SELECT ${crypto.randomUUID()}, ${userId}, r.id, ${input.category}, ${input.context ?? null}, ${now}
    FROM ai_responses r
    WHERE r.id = ${responseId} AND r.user_id = ${userId}
    ON CONFLICT (user_id, response_id) DO NOTHING
    RETURNING id`);
  if (inserted.rows.length > 0) return { created: true };

  const owned = await db.execute(sql`
    SELECT 1 FROM ai_responses WHERE id = ${responseId} AND user_id = ${userId}`);
  if (owned.rows.length === 0) throw responseNotFound();
  return { created: false };
}

export interface StoredAiReport {
  id: string;
  payload: unknown;
  model: string;
  createdAt: number;
}

/** The user's cached intelligent report for (kind, fingerprint), or null. Never another user's. */
export async function findAiReport(
  db: Db,
  userId: string,
  kind: string,
  sourceFingerprint: string,
): Promise<StoredAiReport | null> {
  const result = await db.execute(sql`
    SELECT id, payload, model, created_at FROM ai_reports
    WHERE user_id = ${userId} AND kind = ${kind} AND source_fingerprint = ${sourceFingerprint}`);
  const row = result.rows[0] as { id: string; payload: unknown; model: string; created_at: string | number } | undefined;
  return row ? { id: row.id, payload: row.payload, model: row.model, createdAt: Number(row.created_at) } : null;
}

/**
 * Stores a freshly generated report and drops the user's older reports of the same kind (a new
 * fingerprint invalidates them: only the current source's report is kept). A concurrent insert of the
 * same fingerprint keeps the first row, which is returned.
 */
export async function storeAiReport(
  db: Db,
  userId: string,
  kind: string,
  sourceFingerprint: string,
  input: { id: string; payload: unknown; model: string; createdAt: number },
): Promise<StoredAiReport> {
  await db.execute(sql`
    INSERT INTO ai_reports (id, user_id, kind, source_fingerprint, payload, model, created_at)
    VALUES (${input.id}, ${userId}, ${kind}, ${sourceFingerprint}, ${JSON.stringify(input.payload)}::jsonb, ${input.model}, ${input.createdAt})
    ON CONFLICT (user_id, kind, source_fingerprint) DO NOTHING`);
  await db.execute(sql`
    DELETE FROM ai_reports WHERE user_id = ${userId} AND kind = ${kind} AND source_fingerprint <> ${sourceFingerprint}`);
  const stored = await findAiReport(db, userId, kind, sourceFingerprint);
  if (!stored) throw new Error('ai_reports row missing after insert');
  return stored;
}

export interface CoachContextTurn {
  role: 'user' | 'assistant';
  text: string;
}

/**
 * Thread context for the Coach, built server-side from the user's OWN synced `coach_messages`
 * (never from client-supplied history). Takes the newest live user/assistant messages, at most
 * `limitTurns`, and keeps the most recent contiguous run whose total length fits `maxChars`
 * (a message that would overflow the budget ends the window). Returned oldest first.
 * `excludeId` drops the message being answered (it may already have synced) and, when it has,
 * bounds the context to turns created before it.
 *
 * Assistant turns are trusted only when their id is a COMPLETE `ai_responses` row of the same
 * user, and their text is the server-stored `content`, never the synced copy: a client can sync
 * any assistant row it likes, so a forged or edited one never reaches the model. User turns are
 * the user's own words and are kept as synced.
 */
export async function loadCoachContext(
  db: Db,
  userId: string,
  limitTurns: number,
  maxChars: number,
  excludeId?: string,
): Promise<CoachContextTurn[]> {
  if (!Number.isInteger(limitTurns) || limitTurns <= 0 || maxChars <= 0) return [];
  // When the message being answered has synced, only turns created before it count: a regeneration
  // must not see later turns (e.g. the user's next question). If it has not synced (the usual case
  // for a first attempt) there is nothing newer to leak and no bound is applied.
  const result = await db.execute(sql`
    SELECT m.rol, CASE WHEN m.rol = 'assistant' THEN r.content ELSE m.contenido END AS contenido
    FROM coach_messages m
    LEFT JOIN ai_responses r
      ON r.id = m.id AND r.user_id = m.user_id AND r.status = 'complete' AND r.kind = 'coach'
    WHERE m.user_id = ${userId}
      AND m.deleted_at IS NULL
      AND m.rol IN ('user', 'assistant')
      AND m.id <> ${excludeId ?? ''}
      AND (m.rol = 'user' OR r.content IS NOT NULL)
      AND m.created_at < COALESCE(
        (SELECT c.created_at FROM coach_messages c WHERE c.id = ${excludeId ?? ''} AND c.user_id = ${userId}),
        9223372036854775807)
    ORDER BY m.created_at DESC, m.id DESC
    LIMIT ${Math.min(limitTurns, 200)}`);
  const turns: CoachContextTurn[] = [];
  let used = 0;
  for (const row of result.rows as { rol: 'user' | 'assistant'; contenido: string }[]) {
    if (used + row.contenido.length > maxChars) break;
    used += row.contenido.length;
    turns.push({ role: row.rol, text: row.contenido });
  }
  return turns.reverse();
}

/** Outcome of claiming the response to one user message (see claimCoachResponse). */
export type CoachResponseClaim =
  | { status: 'complete'; responseId: string; content: string }
  | { status: 'incomplete'; responseId: string; attemptId: string };

/** Another attempt of the same clientMessageId is generating right now (its lease is live). */
export const responseInProgress = () =>
  new ApiError(409, 'AI_RESPONSE_IN_PROGRESS', 'A response to this message is already being generated', true);
/** The clientMessageId already names a different kind of AI request (e.g. a mesocycle). */
export const clientMessageIdConflict = () =>
  new ApiError(409, 'CLIENT_MESSAGE_ID_CONFLICT', 'This clientMessageId belongs to another AI request', false);

export interface CoachClaimInput {
  /** Id for a brand-new row (ignored when the row exists). */
  newId: string;
  /** Id of this generation attempt; only it can complete the row. */
  attemptId: string;
  now: number;
  /** Absolute expiry of this attempt's lease (now + model timeout + margin). */
  leaseExpiresAt: number;
}

/**
 * Claims the single Coach `ai_responses` row of (userId, clientMessageId):
 * - none yet → inserted `incomplete`, leased to `attemptId`;
 * - incomplete with no live lease (released, or expired) → the same row/id is leased to `attemptId`
 *   (a new attempt of the same response, never a second response);
 * - incomplete with a live lease → 409 AI_RESPONSE_IN_PROGRESS (retryable), the model is not called;
 * - complete → returned with its stored text, to be replayed without the model;
 * - a row of another kind → 409 CLIENT_MESSAGE_ID_CONFLICT.
 * The server never writes the user's message: it lives only in the client's synced `coach_messages`.
 */
export async function claimCoachResponse(
  db: Db,
  userId: string,
  clientMessageId: string,
  input: CoachClaimInput,
): Promise<CoachResponseClaim> {
  return claimAiResponse(db, userId, clientMessageId, 'coach', input);
}

/** Kinds of `ai_responses` rows (CHECK ai_responses_kind_check). */
export type AiResponseKind = 'coach' | 'mesocycle';

/**
 * claimCoachResponse for any kind: one row per (user, client id), leased to one attempt at a time.
 * A client id already used by another kind → 409 CLIENT_MESSAGE_ID_CONFLICT. `content` of a complete
 * row is the stored result (Coach text, or the validated mesocycle draft as JSON).
 */
export async function claimAiResponse(
  db: Db,
  userId: string,
  clientId: string,
  kind: AiResponseKind,
  input: CoachClaimInput,
): Promise<CoachResponseClaim> {
  const { newId, attemptId, now, leaseExpiresAt } = input;
  const claimed = await db.execute(sql`
    INSERT INTO ai_responses
      (id, user_id, client_message_id, kind, status, content, attempt_id, lease_expires_at, created_at, updated_at)
    VALUES (${newId}, ${userId}, ${clientId}, ${kind}, 'incomplete', NULL, ${attemptId}, ${leaseExpiresAt}, ${now}, ${now})
    ON CONFLICT (user_id, client_message_id)
    DO UPDATE SET attempt_id = EXCLUDED.attempt_id, lease_expires_at = EXCLUDED.lease_expires_at,
                  updated_at = EXCLUDED.updated_at
    WHERE ai_responses.kind = ${kind}
      AND ai_responses.status = 'incomplete'
      AND (ai_responses.lease_expires_at IS NULL OR ai_responses.lease_expires_at <= ${now})
    RETURNING id`);
  const fresh = claimed.rows[0] as { id: string } | undefined;
  if (fresh) return { status: 'incomplete', responseId: fresh.id, attemptId };

  const existing = await db.execute(sql`
    SELECT id, kind, status, content FROM ai_responses
    WHERE user_id = ${userId} AND client_message_id = ${clientId}`);
  const row = existing.rows[0] as { id: string; kind: string; status: string; content: string | null } | undefined;
  if (!row) throw new Error('ai_responses claim found no row');
  if (row.kind !== kind) throw clientMessageIdConflict();
  if (row.status === 'incomplete') throw responseInProgress();
  if (row.content === null) throw new Error(`complete ${kind} response without content`);
  return { status: 'complete', responseId: row.id, content: row.content };
}

/**
 * Stores the final text and marks the response complete, only for the attempt that holds it.
 * Returns false when this attempt no longer owns the row (it was taken over after its lease expired,
 * or the row is already complete): the caller must not report its own text as the response.
 */
export async function completeCoachResponse(
  db: Db,
  userId: string,
  responseId: string,
  attemptId: string,
  content: string,
  now: number,
): Promise<boolean> {
  const updated = await db.execute(sql`
    UPDATE ai_responses
    SET status = 'complete', content = ${content}, attempt_id = NULL, lease_expires_at = NULL, updated_at = ${now}
    WHERE id = ${responseId} AND user_id = ${userId} AND status = 'incomplete' AND attempt_id = ${attemptId}
    RETURNING id`);
  return updated.rows.length > 0;
}

/** Ends this attempt without a response, so a retry can start at once instead of waiting the lease. */
export async function releaseCoachResponse(
  db: Db,
  userId: string,
  responseId: string,
  attemptId: string,
  now: number,
): Promise<void> {
  await db.execute(sql`
    UPDATE ai_responses SET lease_expires_at = NULL, updated_at = ${now}
    WHERE id = ${responseId} AND user_id = ${userId} AND status = 'incomplete' AND attempt_id = ${attemptId}`);
}
