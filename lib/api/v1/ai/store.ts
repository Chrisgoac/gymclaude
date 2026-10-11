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
