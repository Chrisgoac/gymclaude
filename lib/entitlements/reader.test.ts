// @vitest-environment node
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { sql } from 'drizzle-orm';
import type { PoolDb } from '@/db/pool';
import {
  ENTITLEMENT_STATES,
  PRO_GRANTING_STATES,
  createDbEntitlementReader,
  grantsPro,
  type EntitlementState,
} from './reader';

const NOW = 1_767_225_600_000;
const DAY = 86_400_000;

describe('grantsPro (state mapping)', () => {
  it('grants only ACTIVE and GRACE', () => {
    expect([...PRO_GRANTING_STATES].sort()).toEqual(['ACTIVE', 'GRACE']);
    for (const state of ENTITLEMENT_STATES) {
      const expected = state === 'ACTIVE' || state === 'GRACE';
      expect(grantsPro({ state, expiresAt: NOW + DAY }, NOW)).toBe(expected);
    }
  });

  it('never grants once expires_at has passed, even if the state still says ACTIVE/GRACE', () => {
    expect(grantsPro({ state: 'ACTIVE', expiresAt: NOW }, NOW)).toBe(false);
    expect(grantsPro({ state: 'GRACE', expiresAt: NOW - 1 }, NOW)).toBe(false);
    expect(grantsPro({ state: 'ACTIVE', expiresAt: NOW + 1 }, NOW)).toBe(true);
  });
});

describe('createDbEntitlementReader (DB failure)', () => {
  it('rejects (never resolves false/true) when the store errors', async () => {
    const broken = { execute: async () => { throw new Error('connection refused'); } };
    const reader = createDbEntitlementReader(() => broken as unknown as PoolDb);
    await expect(reader.isPro('user_1', NOW)).rejects.toThrow();
  });

  it('rejects when the pool cannot even be created', async () => {
    const reader = createDbEntitlementReader(() => { throw new Error('DATABASE_URL is not set'); });
    await expect(reader.isPro('user_1', NOW)).rejects.toThrow();
  });
});

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('createDbEntitlementReader (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];
  const newUser = () => {
    const u = `test-ent-${crypto.randomUUID()}`;
    users.push(u);
    return u;
  };
  const insert = (userId: string, state: EntitlementState, expiresAt: number, token = crypto.randomUUID()) =>
    db.execute(sql`
      INSERT INTO subscription_entitlements
        (user_id, product_id, purchase_token_hash, state, expires_at, auto_renewing, last_verified_at, source, created_at, updated_at)
      VALUES (${userId}, 'gymlog_pro_monthly', ${token}, ${state}, ${expiresAt}, true, ${NOW}, 'test', ${NOW}, ${NOW})`);

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
      await db.execute(sql`DELETE FROM subscription_entitlements WHERE user_id IN (${list})`);
    }
    await pool.end();
  });

  const reader = () => createDbEntitlementReader(() => db);

  it('active and grace entitlements grant Pro', async () => {
    const a = newUser();
    const g = newUser();
    await insert(a, 'ACTIVE', NOW + DAY);
    await insert(g, 'GRACE', NOW + DAY);
    expect(await reader().isPro(a, NOW)).toBe(true);
    expect(await reader().isPro(g, NOW)).toBe(true);
  });

  it('expired, revoked, on-hold, paused and canceled entitlements do not', async () => {
    const expired = newUser();
    await insert(expired, 'ACTIVE', NOW - 1);
    expect(await reader().isPro(expired, NOW)).toBe(false);
    for (const state of ['EXPIRED', 'REVOKED', 'ON_HOLD', 'PAUSED', 'CANCELED'] as const) {
      const u = newUser();
      await insert(u, state, NOW + DAY);
      expect(await reader().isPro(u, NOW), state).toBe(false);
    }
  });

  it('a missing entitlement is not Pro', async () => {
    expect(await reader().isPro(newUser(), NOW)).toBe(false);
  });

  it("never reads another user's entitlement", async () => {
    const owner = newUser();
    await insert(owner, 'ACTIVE', NOW + DAY);
    expect(await reader().isPro(newUser(), NOW)).toBe(false);
  });

  it('one granting row among revoked ones is enough', async () => {
    const u = newUser();
    await insert(u, 'REVOKED', NOW + DAY);
    await insert(u, 'ACTIVE', NOW + DAY);
    expect(await reader().isPro(u, NOW)).toBe(true);
  });

  it('rejects unknown states and duplicate purchase tokens per user at the database', async () => {
    const u = newUser();
    await expect(insert(u, 'LIFETIME' as EntitlementState, NOW + DAY)).rejects.toThrow();
    await insert(u, 'ACTIVE', NOW + DAY, 'same-token');
    await expect(insert(u, 'ACTIVE', NOW + DAY, 'same-token')).rejects.toThrow();
  });
});
