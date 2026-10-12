// @vitest-environment node
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
// The account-deletion access lock has its own tests (app/api/v1/account, lib/account).
vi.mock('@/lib/account/access', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/account/access')>()),
  assertAccountActive: async () => {},
}));
import { sql } from 'drizzle-orm';
import type { PoolDb } from '@/db/pool';
import { setAuthSourceForTests } from '@/lib/api/v1/auth';
import { RateLimitedError } from '@/lib/api/v1/errors';
import { ApiErrorBodySchema } from '@/lib/api/v1/contracts/common';
import { AI_REPORT_CATEGORIES } from '@/lib/api/v1/ai/contracts';

const h = vi.hoisted(() => ({
  db: undefined as unknown,
  isPro: vi.fn<(...args: unknown[]) => Promise<boolean>>(async () => false),
  rate: vi.fn<(...args: unknown[]) => Promise<void>>(async () => {}),
}));
vi.mock('@/lib/api/v1/rate-limit', () => ({ enforceRateLimit: (...a: unknown[]) => h.rate(...a) }));
vi.mock('@/lib/entitlements/reader', () => ({
  createDbEntitlementReader: () => ({ isPro: (...a: unknown[]) => h.isPro(...a) }),
}));
vi.mock('@/db/pool', () => ({ getPoolDb: () => h.db }));

import { POST } from './route';

const USER = 'user_1';
const RID = '9a7e1f7c-0d5b-4b3e-8a51-3d9b1f0c6e22';
const report = (id: string, payload: unknown, headers: Record<string, string> = { 'content-type': 'application/json' }) =>
  POST(new Request(`http://x/api/v1/ai/responses/${id}/report`, {
    method: 'POST',
    headers,
    body: typeof payload === 'string' ? payload : JSON.stringify(payload),
  }));
const errorOf = async (res: Response) => ApiErrorBodySchema.parse(await res.json()).error;

/** Records each SQL call; answers as reportAiResponse's two queries expect. */
/** Calls in order: conditional insert, ownership check, account-deletion check. */
function fakeDb(inserted: boolean, owned = true, deleting = false) {
  const execute = vi.fn(async (q: unknown) => {
    void q;
    const n = execute.mock.calls.length;
    if (n === 1) return { rows: inserted ? [{ id: 'rep' }] : [] };
    if (n === 2) return { rows: owned ? [{ '?column?': 1 }] : [] };
    return { rows: deleting ? [{ '?column?': 1 }] : [] };
  });
  return { execute };
}

beforeEach(() => {
  setAuthSourceForTests(async () => ({ userId: USER }));
});
afterEach(() => {
  setAuthSourceForTests(null);
  vi.clearAllMocks();
});

describe('POST /api/v1/ai/responses/:id/report', () => {
  it('201 on a first report; needs no Pro (the entitlement reader is never asked)', async () => {
    h.db = fakeDb(true);
    const res = await report(RID, { category: 'medical', context: 'Me sugirió una dosis' });
    expect(res.status).toBe(201);
    expect(await res.json()).toEqual({ responseId: RID, created: true });
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(h.rate).toHaveBeenCalledWith('ai', USER);
    expect(h.rate).toHaveBeenCalledWith('aiDaily', USER);
    expect(h.isPro).not.toHaveBeenCalled();
  });

  it('403 ACCOUNT_DELETING when the insert was refused because the account is being deleted', async () => {
    h.db = fakeDb(false, true, true);
    const res = await report(RID, { category: 'other' });
    expect(res.status).toBe(403);
    expect((await res.json()).error.code).toBe('ACCOUNT_DELETING');
  });

  it('200 created:false on a duplicate report of an owned response', async () => {
    h.db = fakeDb(false, true);
    const res = await report(RID, { category: 'other' });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ responseId: RID, created: false });
  });

  it('404 RESPONSE_NOT_FOUND for an unknown/foreign id and for a malformed one', async () => {
    h.db = fakeDb(false, false);
    const res = await report(RID, { category: 'other' });
    expect(res.status).toBe(404);
    expect(await errorOf(res)).toMatchObject({ code: 'RESPONSE_NOT_FOUND', retryable: false });
    h.db = fakeDb(true);
    const malformed = await report('not-a-uuid', { category: 'other' });
    expect(malformed.status).toBe(404);
    expect((h.db as ReturnType<typeof fakeDb>).execute).not.toHaveBeenCalled();
  });

  it('400 for an unknown category, extra fields or oversized context; 415 for non-JSON', async () => {
    h.db = fakeDb(true);
    for (const bad of [
      { category: 'spam' },
      { category: 'other', userId: 'someone' },
      { category: 'other', context: 'x'.repeat(501) },
      {},
    ]) {
      const res = await report(RID, bad);
      expect(res.status).toBe(400);
      expect((await errorOf(res)).code).toBe('INVALID_REQUEST');
    }
    expect((await report(RID, { category: 'other' }, { 'content-type': 'text/plain' })).status).toBe(415);
    expect((h.db as ReturnType<typeof fakeDb>).execute).not.toHaveBeenCalled();
  });

  it('401 without a principal and 429 when rate limited, before touching the database', async () => {
    h.db = fakeDb(true);
    setAuthSourceForTests(async () => ({ userId: null }));
    expect((await report(RID, { category: 'other' })).status).toBe(401);
    setAuthSourceForTests(async () => ({ userId: USER }));
    h.rate.mockRejectedValueOnce(new RateLimitedError(3));
    const limited = await report(RID, { category: 'other' });
    expect(limited.status).toBe(429);
    expect((h.db as ReturnType<typeof fakeDb>).execute).not.toHaveBeenCalled();
  });
});

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('POST /api/v1/ai/responses/:id/report (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];
  const newUser = () => {
    const u = `test-report-${crypto.randomUUID()}`;
    users.push(u);
    return u;
  };
  const response = async (userId: string) => {
    const id = crypto.randomUUID();
    await db.execute(sql`
      INSERT INTO ai_responses (id, user_id, client_message_id, status, content, created_at, updated_at)
      VALUES (${id}, ${userId}, ${crypto.randomUUID()}, 'complete', 'texto', 1, 1)`);
    return id;
  };
  const reports = async (responseId: string) =>
    (await db.execute(sql`SELECT user_id, category, context FROM reported_ai_responses WHERE response_id = ${responseId}`)).rows;

  beforeAll(async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    const schema = await import('@/db/schema');
    pool = new Pool({ connectionString: url, max: 2 });
    db = drizzle(pool, { schema });
  });
  beforeEach(() => {
    h.db = db;
  });
  afterAll(async () => {
    if (!db) return;
    if (users.length) {
      const list = sql.join(users.map((u) => sql`${u}`), sql`, `);
      for (const t of ['reported_ai_responses', 'ai_responses']) {
        await db.execute(sql`DELETE FROM ${sql.identifier(t)} WHERE user_id IN (${list})`);
      }
    }
    await pool.end();
  });

  it('owner reports once per response, any category; duplicates are idempotent', async () => {
    const u = newUser();
    setAuthSourceForTests(async () => ({ userId: u }));
    for (const category of AI_REPORT_CATEGORIES) {
      const id = await response(u);
      expect((await report(id, { category })).status).toBe(201);
      expect((await report(id, { category: 'other', context: 'otra vez' })).status).toBe(200);
      expect(await reports(id)).toEqual([{ user_id: u, category, context: null }]);
    }
  });

  it("another user's response is a 404 and stores nothing", async () => {
    const owner = newUser();
    const id = await response(owner);
    setAuthSourceForTests(async () => ({ userId: newUser() }));
    const res = await report(id, { category: 'harmful' });
    expect(res.status).toBe(404);
    expect(await reports(id)).toEqual([]);
  });
});
