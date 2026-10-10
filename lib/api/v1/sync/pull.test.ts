// @vitest-environment node
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { sql } from 'drizzle-orm';
import type { PoolDb, PoolTx } from '@/db/pool';
import type { PushRequest, SyncOperationDto, SyncTableName } from '@/lib/api/v1/contracts/sync';
import { PullResponseSchema, SYNC_TABLE_NAMES } from '@/lib/api/v1/contracts/sync';
import fixture from '@/contracts/v1/fixtures/pull-pagination-cases.json';
import { decodeCursor, encodeCursor } from './cursor';
import { pullChanges } from './pull';
import { pushMutations } from './push';
import { SYNC_TABLE_ADAPTERS } from './tables';

const NOW = 1_767_225_600_000;
const uuid = () => crypto.randomUUID();

describe('pullChanges (unit)', () => {
  it('rejects a malformed cursor before touching the database', async () => {
    const untouchable = new Proxy({}, { get: () => { throw new Error('db touched'); } }) as unknown as PoolTx;
    await expect(pullChanges(untouchable, { userId: 'u' }, '!!!', 10)).rejects.toMatchObject({
      status: 400, code: 'INVALID_CURSOR',
    });
  });
});

// Minimal valid record per table (references to unknown ids are tolerated by push).
const records: { [K in SyncTableName]: (id: string, over: { deletedAt: number | null }) => Record<string, unknown> } = {
  exercises: (id, o) => ({ id, updatedAt: NOW - 10, ...o, nombre: 'E', grupoMuscular: 'g', equipamiento: 'e', tipo: 't', esPersonalizado: true }),
  routines: (id, o) => ({ id, updatedAt: NOW - 10, ...o, nombre: 'R', archivada: false }),
  routineExercises: (id, o) => ({ id, updatedAt: NOW - 10, ...o, exerciseId: 'x', orden: 1 }),
  workoutSessions: (id, o) => ({ id, updatedAt: NOW - 10, ...o, fecha: NOW - 100 }),
  loggedExercises: (id, o) => ({ id, updatedAt: NOW - 10, ...o, sessionId: 's', exerciseId: 'x', orden: 0 }),
  loggedSets: (id, o) => ({ id, updatedAt: NOW - 10, ...o, loggedExerciseId: 'l', orden: 0, peso: 50.5, reps: 8 }),
  gyms: (id, o) => ({ id, updatedAt: NOW - 10, ...o, nombre: 'G', orden: 0, archivada: false }),
  exercisePhotos: (id, o) => ({ id, updatedAt: NOW - 10, ...o, exerciseId: 'x', url: 'u', key: 'k' }),
  userSettings: (id, o) => ({ id, updatedAt: NOW - 10, ...o, valor: 'v' }),
  coachMessages: (id, o) => ({ id, updatedAt: NOW - 10, ...o, rol: 'user', contenido: 'hi', createdAt: NOW - 20 }),
  bodyMetrics: (id, o) => ({ id, updatedAt: NOW - 10, ...o, tipo: 'peso', valor: 80, fecha: NOW - 30 }),
  progressPhotos: (id, o) => ({ id, updatedAt: NOW - 10, ...o, url: 'u', key: 'k', fecha: NOW - 30, angulo: 'frente' }),
  mesocycles: (id, o) => ({ id, updatedAt: NOW - 10, ...o, nombre: 'M', objetivo: 'o', semanas: 4, diasPorSemana: 3, progresion: { a: [1, 2] }, fechaInicio: NOW - 40 }),
  achievements: (id, o) => ({ id, updatedAt: NOW - 10, ...o, clave: 'c', fechaDesbloqueo: NOW - 50 }),
};

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('pullChanges (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];
  const newUser = () => {
    const u = `test-pull-${uuid()}`;
    users.push(u);
    return u;
  };

  beforeAll(async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    const schema = await import('@/db/schema');
    pool = new Pool({ connectionString: url, max: 4 });
    db = drizzle(pool, { schema });
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

  const push = (userId: string, ops: SyncOperationDto[]) => {
    const req: PushRequest = { mutations: [{ mutationId: uuid(), deviceId: 'dev', operations: ops }] };
    return db.transaction((tx) => pushMutations(tx, { userId }, req, { now: NOW }));
  };
  const op = (table: SyncTableName, id: string, deletedAt: number | null = null, base: string | null = null) =>
    ({ table, baseServerVersion: base, record: records[table](id, { deletedAt }) }) as unknown as SyncOperationDto;
  const pull = (userId: string, cursor: string | null, limit: number) =>
    db.transaction((tx) => pullChanges(tx, { userId }, cursor, limit));

  it('streams 1,001 interleaved changes across all 14 tables with no gaps or duplicates', async () => {
    const { totalChanges, pageSize, expectedPageSizes } = fixture.pagination;
    const userId = newUser();
    const other = newUser();
    const expected: string[] = []; // table:id in journal order
    const tombstones = new Set<string>();

    for (let i = 0; i < totalChanges; i++) {
      const table = SYNC_TABLE_NAMES[i % SYNC_TABLE_NAMES.length];
      const id = table === 'userSettings' ? `key-${i}` : `${table}-${i}-${uuid()}`;
      const dead = i % 7 === 0 ? NOW - 5 : null;
      await push(userId, [op(table, id, dead)]);
      if (dead) tombstones.add(`${table}:${id}`);
      expected.push(`${table}:${id}`);
      if (i % 50 === 0) await push(other, [op('gyms', `other-${i}-${uuid()}`)]); // interleaved foreign changes
    }

    const seen: string[] = [];
    const pageSizes: number[] = [];
    let cursor: string | null = null;
    const versions: bigint[] = [];
    for (;;) {
      const res = PullResponseSchema.parse(await pull(userId, cursor, pageSize));
      pageSizes.push(res.changes.length);
      for (const c of res.changes) {
        const rec = c.record as Record<string, unknown>;
        seen.push(`${c.table}:${rec.id}`);
        versions.push(BigInt(c.serverVersion));
        expect(rec).not.toHaveProperty('userId');
        expect(rec).not.toHaveProperty('serverUpdatedAt');
        expect(rec).not.toHaveProperty('lastDeviceId');
        expect(rec).not.toHaveProperty('lastMutationId');
        expect(rec).not.toHaveProperty('serverVersion');
        if (tombstones.has(`${c.table}:${rec.id}`)) expect(rec.deletedAt).toBe(NOW - 5);
      }
      cursor = res.cursor;
      if (!res.hasMore) break;
    }

    expect(pageSizes).toEqual(expectedPageSizes);
    expect(seen).toEqual(expected);
    expect(new Set(seen).size).toBe(totalChanges);
    expect(seen.some((s) => s.startsWith('gyms:other-'))).toBe(false);
    expect(versions).toEqual([...versions].sort((a, b) => (a < b ? -1 : 1)));
    expect(tombstones.size).toBeGreaterThan(100);

    // Resuming from the final cursor yields an empty page that echoes it.
    const empty = await pull(userId, cursor, pageSize);
    expect(empty).toEqual({ changes: [], cursor, hasMore: false });
  }, 120_000);

  it('emits only the latest journal entry of a record but still advances over older ones', async () => {
    const userId = newUser();
    const id = `g-${uuid()}`;
    await push(userId, [op('gyms', id)]);
    const first = await pull(userId, null, 10);
    const v1 = first.changes[0].serverVersion;
    await push(userId, [{ ...op('gyms', id), baseServerVersion: v1, record: { ...records.gyms(id, { deletedAt: null }), updatedAt: NOW - 1, nombre: 'Nuevo' } } as never]);

    const all = await pull(userId, null, 10);
    expect(all.changes).toHaveLength(1);
    expect((all.changes[0].record as { nombre: string }).nombre).toBe('Nuevo');
    expect(BigInt(all.changes[0].serverVersion)).toBeGreaterThan(BigInt(v1));

    // limit 1: first page contains only the superseded entry -> zero changes, cursor advances, hasMore.
    const p1 = await pull(userId, null, 1);
    expect(p1.changes).toEqual([]);
    expect(p1.hasMore).toBe(true);
    expect(decodeCursor(p1.cursor)).toBe(BigInt(v1));
    const p2 = await pull(userId, p1.cursor, 1);
    expect(p2.changes).toHaveLength(1);
    expect(p2.hasMore).toBe(false);
  });

  it('returns the input cursor on an empty page and never leaks other users', async () => {
    const userId = newUser();
    const stranger = newUser();
    await push(stranger, [op('gyms', `g-${uuid()}`)]);
    expect(await pull(userId, null, 10)).toEqual({ changes: [], cursor: encodeCursor(BigInt(0)), hasMore: false });
    const c = encodeCursor(BigInt(5));
    expect(await pull(userId, c, 10)).toEqual({ changes: [], cursor: c, hasMore: false });
  });
});
