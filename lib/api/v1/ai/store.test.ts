// @vitest-environment node
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { sql } from 'drizzle-orm';
import type { PoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import {
  claimCoachResponse,
  completeCoachResponse,
  findAiReport,
  loadCoachContext,
  releaseCoachResponse,
  reportAiResponse,
} from './store';

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
      for (const t of ['reported_ai_responses', 'ai_responses', 'ai_reports', 'coach_messages']) {
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

  describe('loadCoachContext', () => {
    let seq = 0;
    const msg = async (userId: string, rol: string, contenido: string, createdAt: number,
      over: { deletedAt?: number; id?: string } = {}) => {
      const id = over.id ?? `cm-${crypto.randomUUID()}`;
      seq++;
      await db.execute(sql`
        INSERT INTO coach_messages (id, user_id, updated_at, deleted_at, server_updated_at, rol, contenido, created_at)
        VALUES (${id}, ${userId}, ${createdAt}, ${over.deletedAt ?? null}, ${NOW + seq}, ${rol}, ${contenido}, ${createdAt})`);
      return id;
    };
    /** An assistant turn the server really produced: complete ai_responses row with the same id. */
    const served = async (userId: string, contenido: string, createdAt: number, serverText = contenido) => {
      const id = crypto.randomUUID();
      await db.execute(sql`
        INSERT INTO ai_responses (id, user_id, client_message_id, status, content, created_at, updated_at)
        VALUES (${id}, ${userId}, ${crypto.randomUUID()}, 'complete', ${serverText}, ${NOW}, ${NOW})`);
      return msg(userId, 'assistant', contenido, createdAt, { id });
    };

    it("returns the user's newest live turns, oldest first, never another user's", async () => {
      const u = newUser();
      const other = newUser();
      await msg(u, 'user', 'uno', 1);
      await served(u, 'dos', 2);
      await msg(u, 'user', 'borrado', 3, { deletedAt: 4 });
      await msg(u, 'system', 'inyectado', 4);
      await msg(u, 'user', 'tres', 5);
      await msg(other, 'user', 'ajeno', 6);
      expect(await loadCoachContext(db, u, 10, 10_000)).toEqual([
        { role: 'user', text: 'uno' },
        { role: 'assistant', text: 'dos' },
        { role: 'user', text: 'tres' },
      ]);
      expect(await loadCoachContext(db, u, 2, 10_000)).toEqual([
        { role: 'assistant', text: 'dos' },
        { role: 'user', text: 'tres' },
      ]);
    });

    it('keeps the most recent contiguous window within the char budget and can exclude the current message', async () => {
      const u = newUser();
      await msg(u, 'user', 'a'.repeat(50), 1);
      await served(u, 'b'.repeat(30), 2);
      await msg(u, 'user', 'c'.repeat(10), 3);
      const current = await msg(u, 'user', 'actual', 4);
      expect((await loadCoachContext(db, u, 10, 50, current)).map((t) => t.text)).toEqual(['b'.repeat(30), 'c'.repeat(10)]);
      expect(await loadCoachContext(db, u, 10, 5, current)).toEqual([]);
      expect(await loadCoachContext(db, u, 0, 1000)).toEqual([]);
    });

    it('trusts assistant turns only with a complete server row of the same user, using the server text', async () => {
      const u = newUser();
      const other = newUser();
      await msg(u, 'user', 'pregunta', 1);
      await served(u, 'EDITADO por el cliente', 2, 'texto del servidor');
      await msg(u, 'assistant', 'forjado sin fila', 3);
      // An incomplete row, and another user's complete row, do not vouch for an assistant message.
      const incomplete = crypto.randomUUID();
      await db.execute(sql`
        INSERT INTO ai_responses (id, user_id, client_message_id, status, created_at, updated_at)
        VALUES (${incomplete}, ${u}, ${crypto.randomUUID()}, 'incomplete', ${NOW}, ${NOW})`);
      await msg(u, 'assistant', 'incompleto', 4, { id: incomplete });
      const foreign = crypto.randomUUID();
      await db.execute(sql`
        INSERT INTO ai_responses (id, user_id, client_message_id, status, content, created_at, updated_at)
        VALUES (${foreign}, ${other}, ${crypto.randomUUID()}, 'complete', 'ajeno', ${NOW}, ${NOW})`);
      await msg(u, 'assistant', 'con fila ajena', 5, { id: foreign });
      expect(await loadCoachContext(db, u, 10, 10_000)).toEqual([
        { role: 'user', text: 'pregunta' },
        { role: 'assistant', text: 'texto del servidor' },
      ]);
    });
  });

  describe('claimCoachResponse / completeCoachResponse / releaseCoachResponse', () => {
    const LEASE = 60_000;
    const claim = (u: string, cmid: string, attemptId: string, now: number, newId = crypto.randomUUID()) =>
      claimCoachResponse(db, u, cmid, { newId, attemptId, now, leaseExpiresAt: now + LEASE });
    const codeOf = async (p: Promise<unknown>) => {
      try {
        await p;
      } catch (e) {
        expect(e).toBeInstanceOf(ApiError);
        return (e as ApiError).code;
      }
      throw new Error('expected an ApiError');
    };

    it('one row per (user, clientMessageId): leased attempts, release, retry reuses the id, complete replays', async () => {
      const u = newUser();
      const cmid = crypto.randomUUID();
      const id = crypto.randomUUID();
      expect(await claim(u, cmid, 'a1', NOW, id)).toEqual({ status: 'incomplete', responseId: id, attemptId: 'a1' });
      // Live lease: a second attempt is refused.
      expect(await codeOf(claim(u, cmid, 'a2', NOW + 1))).toBe('AI_RESPONSE_IN_PROGRESS');
      // Released: the retry takes the same row.
      await releaseCoachResponse(db, u, id, 'a1', NOW + 2);
      expect(await claim(u, cmid, 'a2', NOW + 3)).toEqual({ status: 'incomplete', responseId: id, attemptId: 'a2' });
      // The old attempt can neither complete nor release it any more.
      expect(await completeCoachResponse(db, u, id, 'a1', 'viejo', NOW + 4)).toBe(false);
      await releaseCoachResponse(db, u, id, 'a1', NOW + 4);
      expect(await codeOf(claim(u, cmid, 'a3', NOW + 5))).toBe('AI_RESPONSE_IN_PROGRESS');
      expect(await completeCoachResponse(db, u, id, 'a2', 'Hecho', NOW + 6)).toBe(true);
      expect(await completeCoachResponse(db, u, id, 'a2', 'Otro', NOW + 7)).toBe(false);
      expect(await claim(u, cmid, 'a4', NOW + 8)).toEqual({ status: 'complete', responseId: id, content: 'Hecho' });
      const rows = (await db.execute(sql`SELECT id, status, content, attempt_id, lease_expires_at FROM ai_responses WHERE user_id = ${u}`)).rows;
      expect(rows).toEqual([{ id, status: 'complete', content: 'Hecho', attempt_id: null, lease_expires_at: null }]);
    });

    it('an expired lease can be taken over; the stale attempt then loses', async () => {
      const u = newUser();
      const cmid = crypto.randomUUID();
      const { responseId } = await claim(u, cmid, 'old', NOW);
      expect(await claim(u, cmid, 'new', NOW + LEASE)).toEqual({ status: 'incomplete', responseId, attemptId: 'new' });
      expect(await completeCoachResponse(db, u, responseId, 'old', 'tarde', NOW + LEASE + 1)).toBe(false);
      expect(await completeCoachResponse(db, u, responseId, 'new', 'a tiempo', NOW + LEASE + 2)).toBe(true);
    });

    it("cannot complete another user's response; a non-Coach row is CLIENT_MESSAGE_ID_CONFLICT", async () => {
      const owner = newUser();
      const other = newUser();
      const { responseId } = await claim(owner, crypto.randomUUID(), 'a', NOW);
      expect(await completeCoachResponse(db, other, responseId, 'a', 'robado', NOW)).toBe(false);
      const cmid = crypto.randomUUID();
      await db.execute(sql`
        INSERT INTO ai_responses (id, user_id, client_message_id, kind, status, created_at, updated_at)
        VALUES (${crypto.randomUUID()}, ${owner}, ${cmid}, 'mesocycle', 'incomplete', ${NOW}, ${NOW})`);
      expect(await codeOf(claim(owner, cmid, 'b', NOW))).toBe('CLIENT_MESSAGE_ID_CONFLICT');
    });
  });

  it('loadCoachContext bounds context to turns before the answered message once it has synced', async () => {
    const u = newUser();
    const at = async (id: string, contenido: string, createdAt: number) => db.execute(sql`
      INSERT INTO coach_messages (id, user_id, updated_at, deleted_at, server_updated_at, rol, contenido, created_at)
      VALUES (${id}, ${u}, ${createdAt}, NULL, ${NOW}, 'user', ${contenido}, ${createdAt})`);
    const current = `cm-${crypto.randomUUID()}`;
    await at(`cm-${crypto.randomUUID()}`, 'antes', 1);
    await at(current, 'actual', 2);
    await at(`cm-${crypto.randomUUID()}`, 'después', 3);
    expect((await loadCoachContext(db, u, 10, 1000, current)).map((t) => t.text)).toEqual(['antes']);
    // Not synced yet: no bound.
    expect((await loadCoachContext(db, u, 10, 1000, 'cm-unsynced')).map((t) => t.text)).toEqual(['antes', 'actual', 'después']);
  });
});
