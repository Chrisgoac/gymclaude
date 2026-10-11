// @vitest-environment node
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { sql } from 'drizzle-orm';
import type { PoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import { findAiReport, reportAiResponse } from './store';

const NOW = 1_767_225_600_000;
const url = process.env.SYNC_TEST_DATABASE_URL;

describe.skipIf(!url)('AI store (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];
  const newUser = () => {
    const u = `test-ai-${crypto.randomUUID()}`;
    users.push(u);
    return u;
  };
  const newResponse = async (userId: string) => {
    const id = crypto.randomUUID();
    await db.execute(sql`
      INSERT INTO ai_responses (id, user_id, client_message_id, status, created_at, updated_at)
      VALUES (${id}, ${userId}, ${crypto.randomUUID()}, 'complete', ${NOW}, ${NOW})`);
    return id;
  };
  const reportCount = async (responseId: string) =>
    Number((await db.execute(sql`SELECT count(*) AS n FROM reported_ai_responses WHERE response_id = ${responseId}`)).rows[0].n);

  beforeAll(async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    const schema = await import('@/db/schema');
    pool = new Pool({ connectionString: url, max: 2 });
    db = drizzle(pool, { schema });
  });

  afterAll(async () => {
    if (!db) return;
    if (users.length) {
      const list = sql.join(users.map((u) => sql`${u}`), sql`, `);
      for (const t of ['reported_ai_responses', 'ai_responses', 'ai_reports']) {
        await db.execute(sql`DELETE FROM ${sql.identifier(t)} WHERE user_id IN (${list})`);
      }
    }
    await pool.end();
  });

  it('reports an own response once; a duplicate report is idempotent', async () => {
    const u = newUser();
    const r = await newResponse(u);
    expect(await reportAiResponse(db, u, r, { category: 'inaccurate', context: 'Peso irreal' }, NOW)).toEqual({ created: true });
    expect(await reportAiResponse(db, u, r, { category: 'harmful' }, NOW + 1)).toEqual({ created: false });
    expect(await reportCount(r)).toBe(1);
    const row = (await db.execute(sql`SELECT * FROM reported_ai_responses WHERE response_id = ${r}`)).rows[0];
    expect(row).toMatchObject({ user_id: u, category: 'inaccurate', context: 'Peso irreal' });
  });

  it("cannot report another user's response: 404, indistinguishable from a missing one, nothing stored", async () => {
    const owner = newUser();
    const other = newUser();
    const r = await newResponse(owner);
    for (const id of [r, crypto.randomUUID()]) {
      let caught: unknown;
      try {
        await reportAiResponse(db, other, id, { category: 'other' }, NOW);
      } catch (e) {
        caught = e;
      }
      expect(caught).toBeInstanceOf(ApiError);
      expect(caught).toMatchObject({ status: 404, code: 'RESPONSE_NOT_FOUND', retryable: false });
    }
    expect(await reportCount(r)).toBe(0);
  });

  it("reads intelligent reports only for their owner", async () => {
    const owner = newUser();
    const other = newUser();
    await db.execute(sql`
      INSERT INTO ai_reports (id, user_id, kind, source_fingerprint, payload, model, created_at)
      VALUES (${crypto.randomUUID()}, ${owner}, 'progress', 'fp-1', ${JSON.stringify({ resumen: 'ok' })}::jsonb, 'deepseek-chat', ${NOW})`);
    expect(await findAiReport(db, owner, 'progress', 'fp-1')).toMatchObject({ payload: { resumen: 'ok' }, model: 'deepseek-chat' });
    expect(await findAiReport(db, other, 'progress', 'fp-1')).toBeNull();
    expect(await findAiReport(db, owner, 'progress', 'fp-2')).toBeNull();
  });

  it('enforces the per-user uniqueness the AI routes rely on', async () => {
    const u = newUser();
    const cmid = crypto.randomUUID();
    const ins = () => db.execute(sql`
      INSERT INTO ai_responses (id, user_id, client_message_id, status, created_at, updated_at)
      VALUES (${crypto.randomUUID()}, ${u}, ${cmid}, 'incomplete', ${NOW}, ${NOW})`);
    await ins();
    await expect(ins()).rejects.toThrow();
    // Same clientMessageId for a different user is fine.
    const v = newUser();
    await db.execute(sql`
      INSERT INTO ai_responses (id, user_id, client_message_id, status, created_at, updated_at)
      VALUES (${crypto.randomUUID()}, ${v}, ${cmid}, 'complete', ${NOW}, ${NOW})`);
    await expect(db.execute(sql`
      INSERT INTO ai_responses (id, user_id, client_message_id, status, created_at, updated_at)
      VALUES (${crypto.randomUUID()}, ${u}, ${crypto.randomUUID()}, 'streaming', ${NOW}, ${NOW})`)).rejects.toThrow();
  });
});
