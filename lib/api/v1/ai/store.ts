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

export interface CoachContextTurn {
  role: 'user' | 'assistant';
  text: string;
}

/**
 * Thread context for the Coach, built server-side from the user's OWN synced `coach_messages`
 * (never from client-supplied history). Takes the newest live user/assistant messages, at most
 * `limitTurns`, and keeps the most recent contiguous run whose total length fits `maxChars`
 * (a message that would overflow the budget ends the window). Returned oldest first.
 * `excludeId` drops the message being answered (it may already have synced).
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
  const result = await db.execute(sql`
    SELECT m.rol, CASE WHEN m.rol = 'assistant' THEN r.content ELSE m.contenido END AS contenido
    FROM coach_messages m
    LEFT JOIN ai_responses r
      ON r.id = m.id AND r.user_id = m.user_id AND r.status = 'complete'
    WHERE m.user_id = ${userId}
      AND m.deleted_at IS NULL
      AND m.rol IN ('user', 'assistant')
      AND m.id <> ${excludeId ?? ''}
      AND (m.rol = 'user' OR r.content IS NOT NULL)
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
  | { status: 'incomplete'; responseId: string };

/**
 * Claims the single `ai_responses` row of (userId, clientMessageId). A first request inserts it
 * as `incomplete` with `newId`; a retry of an incomplete one reuses its id (a new attempt of the
 * same response, never a second response); a complete one is returned with its stored text so
 * the caller replays it without calling the model. The server never writes the user's message:
 * it lives only in the client's synced `coach_messages`.
 */
export async function claimCoachResponse(
  db: Db,
  userId: string,
  clientMessageId: string,
  newId: string,
  now: number,
): Promise<CoachResponseClaim> {
  const claimed = await db.execute(sql`
    INSERT INTO ai_responses (id, user_id, client_message_id, status, content, created_at, updated_at)
    VALUES (${newId}, ${userId}, ${clientMessageId}, 'incomplete', NULL, ${now}, ${now})
    ON CONFLICT (user_id, client_message_id)
    DO UPDATE SET updated_at = EXCLUDED.updated_at WHERE ai_responses.status = 'incomplete'
    RETURNING id`);
  const fresh = claimed.rows[0] as { id: string } | undefined;
  if (fresh) return { status: 'incomplete', responseId: fresh.id };

  // The conflict row was not updated, so it is complete (a row never goes back to incomplete).
  const existing = await db.execute(sql`
    SELECT id, status, content FROM ai_responses
    WHERE user_id = ${userId} AND client_message_id = ${clientMessageId}`);
  const row = existing.rows[0] as { id: string; status: string; content: string | null } | undefined;
  if (!row || row.status !== 'complete' || row.content === null) {
    throw new Error('ai_responses claim found no usable row');
  }
  return { status: 'complete', responseId: row.id, content: row.content };
}

/**
 * Stores the final text and marks the response complete. Returns false when the row was no longer
 * incomplete (a concurrent attempt of the same clientMessageId completed first): the caller must
 * not report its own text as the response.
 */
export async function completeCoachResponse(
  db: Db,
  userId: string,
  responseId: string,
  content: string,
  now: number,
): Promise<boolean> {
  const updated = await db.execute(sql`
    UPDATE ai_responses SET status = 'complete', content = ${content}, updated_at = ${now}
    WHERE id = ${responseId} AND user_id = ${userId} AND status = 'incomplete'
    RETURNING id`);
  return updated.rows.length > 0;
}
