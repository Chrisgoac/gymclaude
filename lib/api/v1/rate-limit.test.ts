import { createHash } from 'node:crypto';
import { describe, expect, it, vi } from 'vitest';
import { sql } from 'drizzle-orm';
import { RATE_LIMIT_POLICIES, consumeRateLimit, hashSubject } from './rate-limit';

const fakeDb = (count: number) => ({ execute: vi.fn(async () => ({ rows: [{ count }] })) }) as never;

describe('rate-limit (unit)', () => {
  it('hashes subjects with sha256', () => {
    expect(hashSubject('1.2.3.4')).toBe(createHash('sha256').update('1.2.3.4').digest('hex'));
  });

  it('exposes sync/ai/aiDaily/media/billing/account policies', () => {
    expect(Object.keys(RATE_LIMIT_POLICIES).sort()).toEqual(['account', 'ai', 'aiDaily', 'billing', 'media', 'sync']);
    expect(RATE_LIMIT_POLICIES.aiDaily).toEqual({ limit: 200, windowMs: 86_400_000 });
  });

  it('a spent daily AI budget reports the time left in the 24 h window', async () => {
    const policy = RATE_LIMIT_POLICIES.aiDaily;
    const now = 5 * policy.windowMs + 3_600_000; // 01:00 del día
    const over = await consumeRateLimit('aiDaily', 'u', policy, { db: fakeDb(201), now });
    expect(over).toEqual({ allowed: false, remaining: 0, retryAfterSeconds: 23 * 3600 });
  });

  it('allows up to the limit and reports retry metadata', async () => {
    const policy = { limit: 3, windowMs: 60_000 };
    const now = 120_000 + 15_000;
    const ok = await consumeRateLimit('sync', 'u', policy, { db: fakeDb(3), now });
    expect(ok).toEqual({ allowed: true, remaining: 0, retryAfterSeconds: 45 });
    const over = await consumeRateLimit('sync', 'u', policy, { db: fakeDb(4), now });
    expect(over.allowed).toBe(false);
    expect(over.retryAfterSeconds).toBe(45);
  });

  it('never passes the raw subject to the database', async () => {
    const db = fakeDb(1);
    await consumeRateLimit('sync', 'user_secret', { limit: 1, windowMs: 1000 }, { db, now: 0 });
    const q = JSON.stringify((db as { execute: { mock: { calls: unknown[][] } } }).execute.mock.calls[0][0]);
    expect(q).not.toContain('user_secret');
    expect(q).toContain(hashSubject('user_secret'));
  });
});

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('rate-limit (integration)', () => {
  it('counts concurrent increments atomically and rolls over windows', async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    const pool = new Pool({ connectionString: url, max: 10 });
    const db = drizzle(pool);
    const scope = `t-${crypto.randomUUID()}`;
    const policy = { limit: 5, windowMs: 60_000 };
    try {
      const results = await Promise.all(
        Array.from({ length: 20 }, () => consumeRateLimit(scope, 'subj', policy, { db, now: 1_000_000 })),
      );
      expect(results.filter((r) => r.allowed)).toHaveLength(5);
      const next = await consumeRateLimit(scope, 'subj', policy, { db, now: 1_000_000 + 60_000 });
      expect(next).toMatchObject({ allowed: true, remaining: 4 });
      const rows = await db.execute(sql`SELECT subject_hash, count FROM api_rate_buckets WHERE scope = ${scope} ORDER BY window_start`);
      expect(rows.rows.map((r) => Number(r.count))).toEqual([20, 1]);
      expect(rows.rows.every((r) => r.subject_hash === hashSubject('subj'))).toBe(true);
    } finally {
      await db.execute(sql`DELETE FROM api_rate_buckets WHERE scope = ${scope}`);
      await pool.end();
    }
  });
});
