// @vitest-environment node
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { sql } from 'drizzle-orm';
import type { PoolDb, PoolTx } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import type { PushRequest, SyncOperationDto } from '@/lib/api/v1/contracts/sync';
import { PushResponseSchema, SYNC_TABLE_NAMES } from '@/lib/api/v1/contracts/sync';
import fixture from '@/contracts/v1/fixtures/conflict-cases.json';
import { pushMutations } from './push';
import { SYNC_TABLE_ADAPTERS } from './tables';

const NOW = 1_767_225_600_000;
const uuid = () => crypto.randomUUID();

const gymOp = (id: string, updatedAt: number, over: Partial<{ deletedAt: number | null; nombre: string }> = {},
  baseServerVersion: string | null = null): SyncOperationDto => ({
  table: 'gyms',
  baseServerVersion,
  record: { id, updatedAt, deletedAt: over.deletedAt ?? null, nombre: over.nombre ?? 'Gym', orden: 0, archivada: false },
});

const request = (mutationId: string, operations: SyncOperationDto[], deviceId = 'dev-a'): PushRequest => ({
  mutations: [{ mutationId, deviceId, operations }],
});

/** Captures the thrown ApiError (or fails the test). */
async function expectApiError(p: Promise<unknown>): Promise<ApiError> {
  try {
    await p;
  } catch (e) {
    expect(e).toBeInstanceOf(ApiError);
    return e as ApiError;
  }
  throw new Error('expected an ApiError');
}

describe('tables registry', () => {
  it('covers exactly the 14 client-writable tables', () => {
    expect(Object.keys(SYNC_TABLE_ADAPTERS).sort()).toEqual([...SYNC_TABLE_NAMES].sort());
  });

  it('maps DTO fields explicitly and drops anything else', () => {
    const rec = { id: 'g1', updatedAt: 1, deletedAt: null, nombre: 'A', orden: 1, archivada: false };
    const cols = SYNC_TABLE_ADAPTERS.gyms.toColumns({ ...rec, userId: 'evil', serverVersion: '9' } as never);
    expect(cols).toEqual(rec);
  });

  it('turns omitted optional fields into explicit nulls', () => {
    const cols = SYNC_TABLE_ADAPTERS.routines.toColumns({
      id: 'r1', updatedAt: 1, deletedAt: null, nombre: 'R', archivada: false,
    } as never);
    expect(cols).toMatchObject({ descripcion: null, orden: null, mesocycleId: null });
  });
});

describe('pushMutations (unit)', () => {
  // Any database access fails the test: future-clock rejection happens before touching the DB.
  const untouchable = new Proxy({}, {
    get: () => { throw new Error('db touched'); },
  }) as unknown as PoolTx;

  for (const c of fixture.pushCases.filter((p) => p.kind === 'futureTimestamp' && p.expected.status === 400)) {
    it(c.name, async () => {
      const mutationId = uuid();
      const op = gymOp('g-future', c.incoming!.updatedAt, { deletedAt: c.incoming!.deletedAt });
      const err = await expectApiError(
        pushMutations(untouchable, { userId: 'u' }, request(mutationId, [op]), { now: c.serverNow! }),
      );
      expect(err.status).toBe(c.expected.status);
      expect(err.code).toBe(c.expected.code);
      expect(err.details?.mutationId).toEqual([mutationId]);
    });
  }
});

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('pushMutations (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];
  const newUser = () => {
    const u = `test-push-${uuid()}`;
    users.push(u);
    return u;
  };

  beforeAll(async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    const schema = await import('@/db/schema');
    pool = new Pool({ connectionString: url, max: 6 });
    db = drizzle(pool, { schema });
  });

  afterAll(async () => {
    if (!db) return;
    const list = sql.join(users.map((u) => sql`${u}`), sql`, `);
    if (users.length) {
      for (const name of SYNC_TABLE_NAMES) {
        await db.delete(SYNC_TABLE_ADAPTERS[name].table).where(sql`user_id IN (${list})`);
      }
      await db.execute(sql`DELETE FROM sync_changes WHERE user_id IN (${list})`);
      await db.execute(sql`DELETE FROM sync_mutations WHERE user_id IN (${list})`);
    }
    await pool.end();
  });

  const push = (userId: string, req: PushRequest, now = NOW) =>
    db.transaction((tx) => pushMutations(tx, { userId }, req, { now }));

  const gymRow = async (id: string) =>
    (await db.execute(sql`SELECT * FROM gyms WHERE id = ${id}`)).rows[0] as Record<string, unknown> | undefined;
  const changeCount = async (userId: string) =>
    Number((await db.execute(sql`SELECT count(*) AS n FROM sync_changes WHERE user_id = ${userId}`)).rows[0].n);
  const dedupeCount = async (userId: string) =>
    Number((await db.execute(sql`SELECT count(*) AS n FROM sync_mutations WHERE user_id = ${userId}`)).rows[0].n);

  it('applies a new record with server metadata and journals it', async () => {
    const userId = newUser();
    const mutationId = uuid();
    const gymId = `g-${uuid()}`;
    const res = await push(userId, request(mutationId, [gymOp(gymId, NOW - 1000)], 'pixel-8'));
    expect(PushResponseSchema.parse(res)).toEqual({ acceptedMutationIds: [mutationId], conflicts: [] });

    const row = await gymRow(gymId);
    const change = (await db.execute(sql`SELECT * FROM sync_changes WHERE user_id = ${userId}`)).rows;
    expect(change).toHaveLength(1);
    expect(change[0]).toMatchObject({ table_name: 'gyms', record_id: gymId, changed_at: String(NOW) });
    expect(row).toMatchObject({
      user_id: userId,
      server_version: change[0].sequence,
      last_device_id: 'pixel-8',
      last_mutation_id: mutationId,
      server_updated_at: String(NOW),
      updated_at: String(NOW - 1000),
    });
    expect(await dedupeCount(userId)).toBe(1);
  });

  it('runs each logical mutation in its own nested transaction', async () => {
    const userId = newUser();
    await db.transaction(async (tx) => {
      const spy = vi.spyOn(tx, 'transaction');
      await pushMutations(tx, { userId }, {
        mutations: [
          { mutationId: uuid(), deviceId: 'd', operations: [gymOp(`g-${uuid()}`, NOW), gymOp(`g-${uuid()}`, NOW)] },
          { mutationId: uuid(), deviceId: 'd', operations: [gymOp(`g-${uuid()}`, NOW)] },
          { mutationId: uuid(), deviceId: 'd', operations: [gymOp(`g-${uuid()}`, NOW)] },
        ],
      }, { now: NOW });
      expect(spy).toHaveBeenCalledTimes(3);
    });
  });

  it('a failing second operation rolls back the first and stores no dedupe row', async () => {
    const userId = newUser();
    const other = newUser();
    const foreignRoutine = `r-${uuid()}`;
    await push(other, request(uuid(), [{
      table: 'routines', baseServerVersion: null,
      record: { id: foreignRoutine, updatedAt: NOW, deletedAt: null, nombre: 'ajena', archivada: false },
    }]));

    const okMutation = uuid();
    const badMutation = uuid();
    const okGym = `g-${uuid()}`;
    const rolledBackGym = `g-${uuid()}`;
    const ROLLBACK = new Error('rollback outer');
    let caught: unknown;
    await expect(db.transaction(async (tx) => {
      try {
        await pushMutations(tx, { userId }, {
          mutations: [
            { mutationId: okMutation, deviceId: 'd', operations: [gymOp(okGym, NOW)] },
            {
              mutationId: badMutation, deviceId: 'd', operations: [
                gymOp(rolledBackGym, NOW),
                {
                  table: 'routineExercises', baseServerVersion: null,
                  record: { id: `re-${uuid()}`, updatedAt: NOW, deletedAt: null, routineId: foreignRoutine, exerciseId: 'seed-x', orden: 0 },
                },
              ],
            },
          ],
        }, { now: NOW });
      } catch (e) {
        caught = e;
      }
      // Inside the still-open outer tx: the failed mutation's savepoint left nothing behind.
      const has = async (id: string) => (await tx.execute(sql`SELECT 1 FROM gyms WHERE id = ${id}`)).rows.length;
      expect(await has(okGym)).toBe(1);
      expect(await has(rolledBackGym)).toBe(0);
      const muts = (await tx.execute(sql`SELECT mutation_id FROM sync_mutations WHERE user_id = ${userId}`)).rows;
      expect(muts.map((r) => r.mutation_id)).toEqual([okMutation]);
      const changes = (await tx.execute(sql`SELECT record_id FROM sync_changes WHERE user_id = ${userId}`)).rows;
      expect(changes.map((r) => r.record_id)).toEqual([okGym]);
      throw ROLLBACK;
    })).rejects.toBe(ROLLBACK);

    expect(caught).toBeInstanceOf(ApiError);
    expect((caught as ApiError).status).toBe(400);
    expect((caught as ApiError).code).toBe('INVALID_REFERENCE');
    expect((caught as ApiError).details?.mutationId).toEqual([badMutation]);
    expect(await gymRow(okGym)).toBeUndefined();
    expect(await dedupeCount(userId)).toBe(0);
  });

  it('replays a duplicate mutationId from the stored response (idempotent delivery)', async () => {
    const c = fixture.pushCases.find((p) => p.kind === 'duplicateMutation')!;
    const userId = newUser();
    const gymId = `g-${uuid()}`;
    // Seed a newer row so the first delivery records a conflict that must be replayed verbatim.
    await push(userId, request(uuid(), [gymOp(gymId, NOW, { nombre: 'servidor' })], 'dev-z'));
    const mutationId = uuid();
    const req = request(mutationId, [gymOp(gymId, NOW - 5000, { nombre: 'viejo' }), gymOp(`g-${uuid()}`, NOW)]);

    const first = await push(userId, req);
    const changesAfterFirst = await changeCount(userId);
    const rowAfterFirst = await gymRow(gymId);
    const second = await push(userId, req, NOW + 60_000);

    expect(first.conflicts).toHaveLength(1);
    expect(first.conflicts[0]).toMatchObject({ mutationId, table: 'gyms', recordId: gymId, reason: 'SUPERSEDED' });
    expect(second).toEqual(first);
    expect(c.expected.secondResponseEqualsFirst).toBe(true);
    expect((await changeCount(userId)) - changesAfterFirst).toBe(c.expected.additionalSyncChanges);
    expect(await gymRow(gymId)).toEqual(rowAfterFirst);
    expect(await dedupeCount(userId)).toBe(2);
  });

  it('replays a duplicate mutationId repeated inside the same request', async () => {
    const userId = newUser();
    const mutationId = uuid();
    const gymId = `g-${uuid()}`;
    const m = { mutationId, deviceId: 'd', operations: [gymOp(gymId, NOW)] };
    const res = await push(userId, { mutations: [m, m] });
    expect(res).toEqual({ acceptedMutationIds: [mutationId, mutationId], conflicts: [] });
    expect(await changeCount(userId)).toBe(1);
  });

  it('applies concurrent duplicate deliveries exactly once', async () => {
    const userId = newUser();
    const mutationId = uuid();
    const gymId = `g-${uuid()}`;
    const req = request(mutationId, [gymOp(gymId, NOW)]);
    const [a, b] = await Promise.all([push(userId, req), push(userId, req)]);
    expect(a).toEqual(b);
    expect(a.acceptedMutationIds).toEqual([mutationId]);
    expect(await changeCount(userId)).toBe(1);
    expect(await dedupeCount(userId)).toBe(1);
  });

  it('treats the same mutationId from another user as a different mutation', async () => {
    const a = newUser();
    const b = newUser();
    const mutationId = uuid();
    await push(a, request(mutationId, [gymOp(`g-${uuid()}`, NOW)]));
    await push(b, request(mutationId, [gymOp(`g-${uuid()}`, NOW)]));
    expect(await changeCount(a)).toBe(1);
    expect(await changeCount(b)).toBe(1);
  });

  it('rejects a record id owned by another user without revealing it', async () => {
    const c = fixture.pushCases.find((p) => p.kind === 'crossUserRecordId')!;
    const owner = newUser();
    const attacker = newUser();
    const gymId = `g-${uuid()}`;
    await push(owner, request(uuid(), [gymOp(gymId, NOW - 10, { nombre: 'secreto' })]));
    const before = await gymRow(gymId);

    const mutationId = uuid();
    const err = await expectApiError(push(attacker, request(mutationId, [gymOp(gymId, NOW, { nombre: 'pwned' })])));
    expect(err.status).toBe(c.expected.status);
    expect(err.code).toBe(c.expected.code);
    expect(err.details?.mutationId).toEqual([mutationId]);
    expect(JSON.stringify({ m: err.message, d: err.details })).not.toMatch(/secreto|test-push-/);
    expect(await gymRow(gymId)).toEqual(before);
    expect(await dedupeCount(attacker)).toBe(0);
  });

  it('keys userSettings by (userId, id) so two users can share a setting key', async () => {
    const a = newUser();
    const b = newUser();
    const op = (valor: string): SyncOperationDto => ({
      table: 'userSettings', baseServerVersion: null,
      record: { id: 'restSeconds', updatedAt: NOW, deletedAt: null, valor },
    });
    await push(a, request(uuid(), [op('90')]));
    await push(b, request(uuid(), [op('120')]));
    const rows = (await db.execute(sql`SELECT user_id, valor FROM user_settings WHERE id = 'restSeconds' AND user_id IN (${a}, ${b}) ORDER BY valor`)).rows;
    expect(rows).toEqual([{ user_id: b, valor: '120' }, { user_id: a, valor: '90' }]);
  });

  it('tolerates references to ids unknown to the server (catalog seeds, offline ordering)', async () => {
    const userId = newUser();
    const res = await push(userId, request(uuid(), [{
      table: 'routineExercises', baseServerVersion: null,
      record: { id: `re-${uuid()}`, updatedAt: NOW, deletedAt: null, routineId: `r-${uuid()}`, exerciseId: 'seed-press-banca', orden: 0 },
    }]));
    expect(res.conflicts).toEqual([]);
  });

  it('applies the LWW winner on a stale base and reports STALE_BASE with the new version', async () => {
    const userId = newUser();
    const gymId = `g-${uuid()}`;
    await push(userId, request(uuid(), [gymOp(gymId, NOW - 100)], 'dev-b'));
    const mutationId = uuid();
    const res = await push(userId, request(mutationId, [gymOp(gymId, NOW, { nombre: 'nuevo' }, '1')], 'dev-a'));
    const row = await gymRow(gymId);
    expect(row).toMatchObject({ nombre: 'nuevo', last_mutation_id: mutationId });
    expect(res.conflicts).toEqual([{
      mutationId, table: 'gyms', recordId: gymId, serverVersion: String(row!.server_version), reason: 'STALE_BASE',
    }]);
  });

  it('accepts a matching base without conflict', async () => {
    const userId = newUser();
    const gymId = `g-${uuid()}`;
    await push(userId, request(uuid(), [gymOp(gymId, NOW - 100)]));
    const base = String((await gymRow(gymId))!.server_version);
    const res = await push(userId, request(uuid(), [gymOp(gymId, NOW, {}, base)]));
    expect(res.conflicts).toEqual([]);
    expect(BigInt(String((await gymRow(gymId))!.server_version))).toBeGreaterThan(BigInt(base));
  });

  it('a losing write is reported SUPERSEDED and leaves the row and journal untouched', async () => {
    const userId = newUser();
    const gymId = `g-${uuid()}`;
    await push(userId, request(uuid(), [gymOp(gymId, NOW, { nombre: 'gana' })]));
    const before = await gymRow(gymId);
    const changes = await changeCount(userId);
    const mutationId = uuid();
    const res = await push(userId, request(mutationId, [gymOp(gymId, NOW - 1, { nombre: 'pierde' })]));
    expect(res.acceptedMutationIds).toEqual([mutationId]);
    expect(res.conflicts).toEqual([{
      mutationId, table: 'gyms', recordId: gymId, serverVersion: String(before!.server_version), reason: 'SUPERSEDED',
    }]);
    expect(await gymRow(gymId)).toEqual(before);
    expect(await changeCount(userId)).toBe(changes);
  });

  it('a newer tombstone wins and an older live record does not resurrect it', async () => {
    const userId = newUser();
    const gymId = `g-${uuid()}`;
    await push(userId, request(uuid(), [gymOp(gymId, NOW - 100)]));
    await push(userId, request(uuid(), [gymOp(gymId, NOW - 50, { deletedAt: NOW - 50 })]));
    expect((await gymRow(gymId))!.deleted_at).toBe(String(NOW - 50));
    const res = await push(userId, request(uuid(), [gymOp(gymId, NOW - 80, { nombre: 'zombi' })]));
    expect(res.conflicts[0].reason).toBe('SUPERSEDED');
    expect(await gymRow(gymId)).toMatchObject({ deleted_at: String(NOW - 50), nombre: 'Gym' });
  });

  it('compares against legacy-backfilled metadata', async () => {
    const userId = newUser();
    const gymId = `g-${uuid()}`;
    await db.execute(sql`INSERT INTO gyms (id, user_id, updated_at, deleted_at, server_updated_at, nombre, orden, archivada,
      server_version, last_device_id, last_mutation_id)
      VALUES (${gymId}, ${userId}, ${NOW}, NULL, ${NOW}, 'legacy', 0, false, 5, 'legacy-pwa', 'legacy-backfill:5')`);
    const lose = await push(userId, request(uuid(), [gymOp(gymId, NOW, {}, '5')], 'android-a'));
    expect(lose.conflicts[0].reason).toBe('SUPERSEDED'); // 'android-a' < 'legacy-pwa'
    const win = await push(userId, request(uuid(), [gymOp(gymId, NOW, { nombre: 'android' }, '5')], 'pixel'));
    expect(win.conflicts).toEqual([]); // 'pixel' > 'legacy-pwa'
    expect(await gymRow(gymId)).toMatchObject({ nombre: 'android', last_device_id: 'pixel' });
  });

  it('accepts timestamps exactly at the five-minute tolerance', async () => {
    const c = fixture.pushCases.find((p) => p.kind === 'futureTimestamp' && p.expected.status === 200)!;
    const userId = newUser();
    const res = await push(userId, request(uuid(), [gymOp(`g-${uuid()}`, c.incoming!.updatedAt, { deletedAt: c.incoming!.deletedAt })]), c.serverNow!);
    expect(res.conflicts).toEqual([]);
  });

  it('writes every one of the 14 tables', async () => {
    const userId = newUser();
    const base = (id: string) => ({ id, updatedAt: NOW, deletedAt: null });
    const s = () => `x-${uuid()}`;
    const ops: SyncOperationDto[] = [
      { table: 'exercises', baseServerVersion: null, record: { ...base(s()), nombre: 'n', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: true, incrementoKg: 2.5 } },
      { table: 'routines', baseServerVersion: null, record: { ...base(s()), nombre: 'n', archivada: false, orden: 1 } },
      { table: 'routineExercises', baseServerVersion: null, record: { ...base(s()), exerciseId: 'seed-a', orden: 0, repsObjetivoMin: 6 } },
      { table: 'workoutSessions', baseServerVersion: null, record: { ...base(s()), fecha: NOW, duracionSegundos: 60 } },
      { table: 'loggedExercises', baseServerVersion: null, record: { ...base(s()), sessionId: 's', exerciseId: 'e', orden: 0 } },
      { table: 'loggedSets', baseServerVersion: null, record: { ...base(s()), loggedExerciseId: 'le', orden: 0, peso: 80.5, reps: 8, esCalentamiento: true } },
      { table: 'gyms', baseServerVersion: null, record: { ...base(s()), nombre: 'g', orden: 0, archivada: false } },
      { table: 'exercisePhotos', baseServerVersion: null, record: { ...base(s()), exerciseId: 'e', url: 'https://x/y', key: 'k' } },
      { table: 'userSettings', baseServerVersion: null, record: { ...base('modoProgresion'), valor: '"doble"' } },
      { table: 'coachMessages', baseServerVersion: null, record: { ...base(s()), rol: 'user', contenido: 'hola', createdAt: NOW } },
      { table: 'bodyMetrics', baseServerVersion: null, record: { ...base(s()), tipo: 'peso', valor: 80.1, fecha: NOW } },
      { table: 'progressPhotos', baseServerVersion: null, record: { ...base(s()), url: 'https://x/p', key: 'p', fecha: NOW, angulo: 'frente' } },
      { table: 'mesocycles', baseServerVersion: null, record: { ...base(s()), nombre: 'm', objetivo: 'o', semanas: 4, diasPorSemana: 3, progresion: [{ semana: 1, descarga: false, ajuste: '' }], fechaInicio: NOW } },
      { table: 'achievements', baseServerVersion: null, record: { ...base(s()), clave: 'first', fechaDesbloqueo: NOW } },
    ];
    const res = await push(userId, request(uuid(), ops));
    expect(res.conflicts).toEqual([]);
    const tables = (await db.execute(sql`SELECT table_name FROM sync_changes WHERE user_id = ${userId} ORDER BY sequence`)).rows;
    expect(tables.map((r) => r.table_name)).toEqual(ops.map((o) => o.table));
    const meso = (await db.execute(sql`SELECT progresion FROM mesocycles WHERE user_id = ${userId}`)).rows[0];
    expect(meso.progresion).toEqual([{ semana: 1, descarga: false, ajuste: '' }]);
  });
});
