import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { and, eq, sql } from 'drizzle-orm';
import type { PoolDb } from '@/db/pool';

const auth = vi.fn();
vi.mock('@clerk/nextjs/server', () => ({ auth: () => auth() }));
// La BD la decide cada test: un doble en los unitarios, la BD desechable en los de integración.
let currentDb: unknown;
vi.mock('@/db/pool', () => ({ getPoolDb: () => currentDb }));
vi.mock('@/lib/api/v1/rate-limit', () => ({ enforceRateLimit: vi.fn(async () => {}) }));
vi.mock('@/lib/api/v1/sync/lock', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api/v1/sync/lock')>();
  return { lockUserSync: vi.fn(actual.lockUserSync) };
});
vi.mock('@/lib/api/v1/sync/legacy', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api/v1/sync/legacy')>();
  return { ...actual, applyLegacyPush: vi.fn(actual.applyLegacyPush) };
});

import { POST } from './route';
import { GET as pullV1 } from '@/app/api/v1/sync/pull/route';
import { POST as pushV1 } from '@/app/api/v1/sync/push/route';
import { PullResponseSchema, PushResponseSchema, SYNC_TABLE_NAMES } from '@/lib/api/v1/contracts/sync';
import { applyLegacyPush } from '@/lib/api/v1/sync/legacy';
import { lockUserSync } from '@/lib/api/v1/sync/lock';
import { SYNC_TABLE_ADAPTERS } from '@/lib/api/v1/sync/tables';

const legacyPush = (body: unknown) =>
  POST(new Request('http://x/api/sync/push', { method: 'POST', body: JSON.stringify(body) }));

beforeEach(() => auth.mockReset());
afterEach(() => vi.clearAllMocks());

describe('POST /api/sync/push (legacy PWA, unit)', () => {
  const calls: string[] = [];
  const fakeTx = { tag: 'tx' };
  beforeEach(() => {
    calls.length = 0;
    currentDb = {
      transaction: async (fn: (tx: unknown) => Promise<unknown>) => {
        calls.push('begin');
        return fn(fakeTx);
      },
    };
    vi.mocked(lockUserSync).mockImplementation(async () => {
      calls.push('lock');
    });
    vi.mocked(applyLegacyPush).mockImplementation(async () => {
      calls.push('apply');
    });
  });
  // mockReset devuelve la implementación real para los tests de integración.
  afterEach(() => {
    vi.mocked(lockUserSync).mockReset();
    vi.mocked(applyLegacyPush).mockReset();
  });

  it('keeps the plain-text 401 without a session', async () => {
    auth.mockResolvedValue({ userId: null });
    const res = await legacyPush([]);
    expect(res.status).toBe(401);
    expect(await res.text()).toBe('Unauthorized');
    expect(calls).toEqual([]);
  });

  it('accepts the PWA TableChanges[] shape and answers { ok: true }', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const changes = [{ table: 'gyms', records: [{ id: 'g1', updatedAt: 5, deletedAt: null, nombre: 'G', orden: 0, archivada: false }] }];
    const res = await legacyPush(changes);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
    expect(calls).toEqual(['begin', 'lock', 'apply']);
    expect(lockUserSync).toHaveBeenCalledWith(fakeTx, 'u1');
    const [tx, userId, passed, options] = vi.mocked(applyLegacyPush).mock.calls[0];
    expect([tx, userId, passed]).toEqual([fakeTx, 'u1', changes]);
    expect(options.mutationId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(typeof options.now).toBe('number');
  });

  it('reads the write timestamp inside the transaction, after taking the sync lock', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const now = vi.spyOn(Date, 'now').mockImplementation(() => {
      calls.push('now');
      return 4242;
    });
    try {
      await legacyPush([]);
    } finally {
      now.mockRestore();
    }
    expect(calls).toEqual(['begin', 'lock', 'now', 'apply']);
    expect(vi.mocked(applyLegacyPush).mock.calls[0][3].now).toBe(4242);
  });

  it('uses a fresh server-generated mutation id per request', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    await legacyPush([]);
    await legacyPush([]);
    const [a, b] = vi.mocked(applyLegacyPush).mock.calls.map((c) => c[3].mutationId);
    expect(a).not.toBe(b);
  });
});

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('POST /api/sync/push (legacy PWA, integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];
  const newUser = () => {
    const u = `test-legacy-${crypto.randomUUID()}`;
    users.push(u);
    return u;
  };
  const gyms = SYNC_TABLE_ADAPTERS.gyms.table;
  const gym = (id: string, updatedAt: number, nombre = 'G') => ({
    id, userId: null, updatedAt, deletedAt: null, nombre, orden: 0, archivada: false,
  });
  const pull = async (cursor?: string) =>
    PullResponseSchema.parse(await (await pullV1(new Request(`http://x/api/v1/sync/pull${cursor ? `?cursor=${cursor}` : ''}`))).json());
  const row = async (userId: string, id: string) =>
    (await db.select().from(gyms).where(and(eq(gyms.id, id), eq(gyms.userId, userId))))[0] as Record<string, unknown> | undefined;

  beforeAll(async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    const schema = await import('@/db/schema');
    pool = new Pool({ connectionString: url, max: 4 });
    db = drizzle(pool, { schema });
  });
  beforeEach(() => {
    currentDb = db;
  });
  afterAll(async () => {
    if (!db) return;
    if (users.length) {
      const list = sql.join(users.map((u) => sql`${u}`), sql`, `);
      for (const name of SYNC_TABLE_NAMES) {
        await db.delete(SYNC_TABLE_ADAPTERS[name].table).where(sql`user_id IN (${list})`);
      }
      await db.execute(sql`DELETE FROM sync_changes WHERE user_id IN (${list})`);
      await db.execute(sql`DELETE FROM sync_mutations WHERE user_id IN (${list})`);
    }
    await pool.end();
  });

  it('journals a legacy write so it appears in the next v1 pull page as legacy-pwa', async () => {
    const userId = newUser();
    auth.mockResolvedValue({ userId });
    const id = `g-${crypto.randomUUID()}`;
    // El cliente intenta colar metadatos de servidor: se ignoran.
    const res = await legacyPush([
      { table: 'gyms', records: [{ ...gym(id, 1000), serverVersion: '999', lastDeviceId: 'evil', serverUpdatedAt: 1 }] },
      { table: 'unknownTable', records: [{ id: 'x', updatedAt: 1, deletedAt: null }] },
    ]);
    expect(await res.json()).toEqual({ ok: true });

    const stored = await row(userId, id);
    expect(stored).toMatchObject({ lastDeviceId: 'legacy-pwa', nombre: 'G' });
    expect(stored!.lastMutationId).toMatch(/^[0-9a-f-]{36}$/);
    expect(stored!.serverVersion).not.toBe(BigInt(999));

    const page = await pull();
    expect(page.changes).toEqual([
      { table: 'gyms', serverVersion: String(stored!.serverVersion), record: { id, updatedAt: 1000, deletedAt: null, nombre: 'G', orden: 0, archivada: false } },
    ]);

    // Un update legacy posterior entra como nuevo cambio; uno más viejo (LWW) no.
    await legacyPush([{ table: 'gyms', records: [gym(id, 2000, 'G2')] }]);
    await legacyPush([{ table: 'gyms', records: [gym(id, 1500, 'old')] }]);
    const next = await pull(page.cursor);
    expect(next.changes).toHaveLength(1);
    expect(next.changes[0].record).toMatchObject({ id, nombre: 'G2', updatedAt: 2000 });
    expect(BigInt(next.changes[0].serverVersion)).toBeGreaterThan(BigInt(page.changes[0].serverVersion));

    // El pull legacy de la PWA sigue avanzando por serverUpdatedAt, que se mantiene.
    expect((await row(userId, id))!.serverUpdatedAt).toBeGreaterThan(1);
  });

  it('a v1 client can build on a legacy write using its serverVersion as base', async () => {
    const userId = newUser();
    auth.mockResolvedValue({ userId });
    const id = `g-${crypto.randomUUID()}`;
    await legacyPush([{ table: 'gyms', records: [gym(id, 1000)] }]);
    const base = (await pull()).changes[0].serverVersion;

    const mutationId = crypto.randomUUID();
    const res = await pushV1(
      new Request('http://x/api/v1/sync/push', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          mutations: [{
            mutationId, deviceId: 'pixel',
            operations: [{ table: 'gyms', baseServerVersion: base, record: { id, updatedAt: 3000, deletedAt: null, nombre: 'V1', orden: 0, archivada: false } }],
          }],
        }),
      }),
    );
    expect(PushResponseSchema.parse(await res.json())).toEqual({ acceptedMutationIds: [mutationId], conflicts: [] });
    expect(await row(userId, id)).toMatchObject({ nombre: 'V1', lastDeviceId: 'pixel' });
  });

  it('serializes concurrent legacy pushes of the same user (per-user sync lock)', async () => {
    const userId = newUser();
    auth.mockResolvedValue({ userId });
    const ids = Array.from({ length: 6 }, () => `g-${crypto.randomUUID()}`);
    const results = await Promise.all(ids.map((id) => legacyPush([{ table: 'gyms', records: [gym(id, 1000)] }])));
    expect(results.map((r) => r.status)).toEqual(ids.map(() => 200));
    const page = await pull();
    expect(page.changes.map((c) => c.record.id).sort()).toEqual([...ids].sort());
  });

  it('skips a record whose id belongs to another user instead of taking it over', async () => {
    const owner = newUser();
    const intruder = newUser();
    const id = `g-${crypto.randomUUID()}`;
    auth.mockResolvedValue({ userId: owner });
    await legacyPush([{ table: 'gyms', records: [gym(id, 1000, 'mine')] }]);

    auth.mockResolvedValue({ userId: intruder });
    const res = await legacyPush([{ table: 'gyms', records: [gym(id, 9000, 'stolen')] }]);
    expect(await res.json()).toEqual({ ok: true });
    expect(await row(owner, id)).toMatchObject({ nombre: 'mine' });
    expect((await pull()).changes).toEqual([]);
  });

  it('writes userSettings (composite key) for two users with the same setting id', async () => {
    const a = newUser();
    const b = newUser();
    for (const userId of [a, b]) {
      auth.mockResolvedValue({ userId });
      await legacyPush([{ table: 'userSettings', records: [{ id: 'units', userId: null, updatedAt: 1000, deletedAt: null, valor: `"${userId}"` }] }]);
      const page = await pull();
      expect(page.changes).toEqual([
        expect.objectContaining({ table: 'userSettings', record: { id: 'units', updatedAt: 1000, deletedAt: null, valor: `"${userId}"` } }),
      ]);
    }
  });
});
