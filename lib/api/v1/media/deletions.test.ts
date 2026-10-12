// @vitest-environment node
import { randomUUID } from 'node:crypto';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import type { PoolDb } from '@/db/pool';
import type { SyncOperationDto } from '@/lib/api/v1/contracts/sync';
import { applyLegacyPush } from '@/lib/api/v1/sync/legacy';
import { lockUserSync } from '@/lib/api/v1/sync/lock';
import { pushMutations } from '@/lib/api/v1/sync/push';
import { MEDIA_DELETION_DELAY_MS, isOwnedMediaKey, isOwnedV1Key, mediaObjectKey } from './deletions';

const NOW = 1_780_000_000_000;

describe('media key ownership', () => {
  it('recognises only the owner v1 namespace (optionally per kind)', () => {
    const key = mediaObjectKey('u1', 'exercise', 'a');
    expect(isOwnedV1Key('u1', key)).toBe(true);
    expect(isOwnedV1Key('u1', key, 'exercise')).toBe(true);
    expect(isOwnedV1Key('u1', key, 'progress')).toBe(false);
    expect(isOwnedV1Key('u2', key)).toBe(false);
    expect(isOwnedV1Key('u1', 'u1/e1/x.jpg')).toBe(false);
    expect(isOwnedV1Key('u1', `${key}/../../other`)).toBe(false);
  });

  it('also accepts the owner legacy PWA namespace for signing/deletion', () => {
    expect(isOwnedMediaKey('u1', 'u1/e1/x.jpg')).toBe(true);
    expect(isOwnedMediaKey('u1', 'u2/e1/x.jpg')).toBe(false);
  });
});

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('sync tombstones enqueue v1 media deletions (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];
  const newUser = () => {
    const u = `test-media-del-${randomUUID()}`;
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
      for (const t of ['exercise_photos', 'progress_photos', 'media_deletions', 'sync_changes', 'sync_mutations']) {
        await db.execute(sql`DELETE FROM ${sql.identifier(t)} WHERE user_id IN (${list})`);
      }
    }
    await pool.end();
  });

  const pending = async (key: string) =>
    (await db.execute(sql`SELECT * FROM media_deletions WHERE object_key = ${key}`)).rows[0] as
      | Record<string, unknown>
      | undefined;

  const photoOp = (id: string, key: string, updatedAt: number, deletedAt: number | null = null): SyncOperationDto => ({
    table: 'exercisePhotos',
    baseServerVersion: null,
    record: { id, updatedAt, deletedAt, exerciseId: 'e1', url: `/api/media/read/${id}`, key },
  });
  const pushV1 = (userId: string, ops: SyncOperationDto[], now = NOW) =>
    db.transaction((tx) =>
      pushMutations(tx, { userId }, { mutations: [{ mutationId: randomUUID(), deviceId: 'pixel', operations: ops }] }, { now }),
    );
  const pushLegacy = (userId: string, table: string, records: Record<string, unknown>[], now = NOW) =>
    db.transaction(async (tx) => {
      await lockUserSync(tx, userId);
      await applyLegacyPush(tx, userId, [{ table, records }] as never, { now, mutationId: randomUUID() });
    });

  it('v1 push: a tombstone of an owned v1 photo enqueues its object in the same transaction', async () => {
    const userId = newUser();
    const id = `ph-${randomUUID()}`;
    const key = mediaObjectKey(userId, 'exercise', randomUUID());
    await pushV1(userId, [photoOp(id, key, NOW - 1000)]);
    expect(await pending(key)).toBeUndefined();

    await pushV1(userId, [photoOp(id, key, NOW - 500, NOW - 500)]);
    expect(await pending(key)).toMatchObject({ user_id: userId, due_at: String(NOW + MEDIA_DELETION_DELAY_MS) });
  });

  it('v1 push: never enqueues foreign or legacy keys, nor a losing tombstone', async () => {
    const userId = newUser();
    const victim = newUser();
    const foreign = mediaObjectKey(victim, 'exercise', randomUUID());
    const legacy = `${userId}/e1/${randomUUID()}.jpg`;
    await pushV1(userId, [photoOp(`f-${randomUUID()}`, foreign, NOW - 10, NOW - 10)]);
    await pushV1(userId, [photoOp(`l-${randomUUID()}`, legacy, NOW - 10, NOW - 10)]);
    expect(await pending(foreign)).toBeUndefined();
    expect(await pending(legacy)).toBeUndefined();

    const id = `ph-${randomUUID()}`;
    const key = mediaObjectKey(userId, 'exercise', randomUUID());
    await pushV1(userId, [photoOp(id, key, NOW - 100)]);
    await pushV1(userId, [photoOp(id, key, NOW - 200, NOW - 200)]); // más antiguo: pierde (LWW)
    expect(await pending(key)).toBeUndefined();
  });

  it('legacy PWA push: tombstoning a v1 photo enqueues it; a live write does not', async () => {
    const userId = newUser();
    const id = `pp-${randomUUID()}`;
    const key = mediaObjectKey(userId, 'progress', randomUUID());
    const base = { id, url: `/api/media/read/${id}`, key, fecha: NOW, angulo: 'frente', nota: null };
    await pushLegacy(userId, 'progressPhotos', [{ ...base, updatedAt: NOW - 1000, deletedAt: null }]);
    expect(await pending(key)).toBeUndefined();
    await pushLegacy(userId, 'progressPhotos', [{ ...base, updatedAt: NOW - 500, deletedAt: NOW - 500 }]);
    expect(await pending(key)).toMatchObject({ user_id: userId });
  });

  it('a replaced key releases the previous v1 object', async () => {
    const userId = newUser();
    const id = `ph-${randomUUID()}`;
    const oldKey = mediaObjectKey(userId, 'exercise', randomUUID());
    const newKey = mediaObjectKey(userId, 'exercise', randomUUID());
    await pushV1(userId, [photoOp(id, oldKey, NOW - 1000)]);
    await pushV1(userId, [photoOp(id, newKey, NOW - 500)]);
    expect(await pending(oldKey)).toMatchObject({ user_id: userId });
    expect(await pending(newKey)).toBeUndefined();
  });
});
