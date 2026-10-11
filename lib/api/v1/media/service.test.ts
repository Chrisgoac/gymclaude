// @vitest-environment node
import { randomUUID } from 'node:crypto';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { PoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import {
  MEDIA_LIMITS,
  MediaDeleteResponseSchema,
  MediaReadGrantSchema,
  MediaUploadResponseSchema,
} from './contracts';
import {
  MEDIA_DELETION_DELAY_MS,
  createMediaService,
  inspectImage,
  mediaObjectKey,
  mediaReadRoute,
  userHash,
  validateMediaBytes,
  type MediaStorage,
} from './service';

// ── Imágenes mínimas válidas (solo cabeceras; el servidor no decodifica píxeles) ──

const u16be = (n: number) => [(n >> 8) & 0xff, n & 0xff];
const u32be = (n: number) => [(n >>> 24) & 0xff, (n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff];
const u24le = (n: number) => [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff];
const ascii = (s: string) => [...s].map((c) => c.charCodeAt(0));

function jpeg(width: number, height: number): Uint8Array {
  const app0 = [0xff, 0xe0, ...u16be(16), ...ascii('JFIF'), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0];
  const sof0 = [0xff, 0xc0, ...u16be(17), 8, ...u16be(height), ...u16be(width), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1];
  return new Uint8Array([0xff, 0xd8, ...app0, ...sof0, 0xff, 0xd9]);
}

function png(width: number, height: number): Uint8Array {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  const ihdr = [...u32be(13), ...ascii('IHDR'), ...u32be(width), ...u32be(height), 8, 6, 0, 0, 0, 0, 0, 0, 0];
  return new Uint8Array([...sig, ...ihdr]);
}

function webp(width: number, height: number): Uint8Array {
  const vp8x = [...ascii('VP8X'), 10, 0, 0, 0, 0, 0, 0, 0, ...u24le(width - 1), ...u24le(height - 1)];
  const body = [...ascii('WEBP'), ...vp8x];
  return new Uint8Array([...ascii('RIFF'), ...u32be(body.length).reverse(), ...body]);
}

const NOW = 1_780_000_000_000;

function fakeStorage() {
  const objects = new Map<string, { bytes: Uint8Array; contentType: string }>();
  const storage: MediaStorage & { objects: typeof objects } = {
    objects,
    putPrivate: vi.fn(async (key: string, bytes: Uint8Array, contentType: string) => {
      objects.set(key, { bytes, contentType });
    }),
    signRead: vi.fn(async (key: string, ttlSeconds: number) => `https://r2.test/${key}?X-Amz-Expires=${ttlSeconds}`),
    remove: vi.fn(async (key: string) => {
      objects.delete(key);
    }),
  };
  return storage;
}

async function expectApiError(p: Promise<unknown>): Promise<ApiError> {
  const err = await p.then(
    () => {
      throw new Error('expected ApiError');
    },
    (e: unknown) => e,
  );
  expect(err).toBeInstanceOf(ApiError);
  return err as ApiError;
}

// Una BD que falla al tocarla: la validación debe cortar antes de cualquier E/S.
const untouchableDb = new Proxy({}, {
  get() {
    throw new Error('database must not be touched');
  },
}) as unknown as PoolDb;

describe('image inspection', () => {
  it('reads JPEG, PNG and WebP dimensions from the header', () => {
    expect(inspectImage(jpeg(1080, 1920))).toEqual({ mime: 'image/jpeg', width: 1080, height: 1920 });
    expect(inspectImage(png(640, 480))).toEqual({ mime: 'image/png', width: 640, height: 480 });
    expect(inspectImage(webp(300, 200))).toEqual({ mime: 'image/webp', width: 300, height: 200 });
  });

  it('returns null for unknown or truncated input', () => {
    expect(inspectImage(new Uint8Array([1, 2, 3, 4]))).toBeNull();
    expect(inspectImage(jpeg(10, 10).slice(0, 12))).toBeNull();
    expect(inspectImage(png(10, 10).slice(0, 20))).toBeNull();
    expect(inspectImage(new TextEncoder().encode('<svg xmlns="http://www.w3.org/2000/svg"/>'))).toBeNull();
  });
});

describe('validateMediaBytes', () => {
  it('accepts a matching MIME (case and parameters ignored, image/jpg alias)', () => {
    expect(validateMediaBytes(jpeg(800, 600), 'IMAGE/JPEG; charset=binary')).toMatchObject({ mime: 'image/jpeg' });
    expect(validateMediaBytes(jpeg(800, 600), 'image/jpg')).toMatchObject({ mime: 'image/jpeg', width: 800 });
  });

  it('rejects a magic-byte/MIME mismatch', () => {
    const err = (() => {
      try {
        validateMediaBytes(png(10, 10), 'image/jpeg');
      } catch (e) {
        return e as ApiError;
      }
      throw new Error('expected error');
    })();
    expect(err).toMatchObject({ status: 400, code: 'MEDIA_TYPE_MISMATCH' });
  });

  it('rejects unsupported declared types and unrecognized content', () => {
    expect(() => validateMediaBytes(jpeg(10, 10), 'image/heic')).toThrow(
      expect.objectContaining({ status: 415, code: 'UNSUPPORTED_MEDIA_TYPE' }),
    );
    expect(() => validateMediaBytes(new Uint8Array([0, 1, 2, 3]), 'image/png')).toThrow(
      expect.objectContaining({ status: 400, code: 'INVALID_MEDIA' }),
    );
    expect(() => validateMediaBytes(new Uint8Array(), 'image/png')).toThrow(
      expect.objectContaining({ status: 400, code: 'INVALID_MEDIA' }),
    );
  });

  it('rejects oversize input before inspecting it', () => {
    const big = new Uint8Array(MEDIA_LIMITS.maxBytes + 1);
    big.set(jpeg(10, 10));
    expect(() => validateMediaBytes(big, 'image/jpeg')).toThrow(
      expect.objectContaining({ status: 413, code: 'MEDIA_TOO_LARGE' }),
    );
  });

  it('rejects absurd decoded dimensions', () => {
    expect(() => validateMediaBytes(png(MEDIA_LIMITS.maxDimension + 1, 10), 'image/png')).toThrow(
      expect.objectContaining({ status: 400, code: 'MEDIA_DIMENSIONS_INVALID' }),
    );
    expect(() => validateMediaBytes(png(8000, 8000), 'image/png')).toThrow(
      expect.objectContaining({ code: 'MEDIA_DIMENSIONS_INVALID' }),
    );
    expect(() => validateMediaBytes(jpeg(0, 10), 'image/jpeg')).toThrow(
      expect.objectContaining({ code: 'MEDIA_DIMENSIONS_INVALID' }),
    );
  });
});

describe('object key namespace', () => {
  it('generates users/<userHash>/<kind>/<uuid> with a stable, non-reversible hash', () => {
    const id = randomUUID();
    const key = mediaObjectKey('user_abc', 'progress', id);
    expect(key).toBe(`users/${userHash('user_abc')}/progress/${id}`);
    expect(userHash('user_abc')).toMatch(/^[0-9a-f]{32}$/);
    expect(userHash('user_abc')).toBe(userHash('user_abc'));
    expect(userHash('user_abc')).not.toBe(userHash('user_abd'));
    expect(key).not.toContain('user_abc');
  });

  it('points the stored url at the authenticated read route, never a public bucket URL', () => {
    expect(mediaReadRoute('p-1')).toBe('/api/media/read/p-1');
  });
});

describe('upload validation (no I/O)', () => {
  it('rejects invalid bytes without touching storage or the database', async () => {
    const storage = fakeStorage();
    const service = createMediaService({ db: untouchableDb, storage, now: () => NOW });
    const err = await expectApiError(
      service.upload('u1', { kind: 'exercise', fields: { exerciseId: 'e1' }, bytes: png(10, 10), declaredMime: 'image/jpeg' }),
    );
    expect(err.code).toBe('MEDIA_TYPE_MISMATCH');
    expect(storage.putPrivate).not.toHaveBeenCalled();
  });

  it('rejects client-supplied metadata that fails the contract', async () => {
    const storage = fakeStorage();
    const service = createMediaService({ db: untouchableDb, storage, now: () => NOW });
    const err = await expectApiError(
      service.upload('u1', {
        kind: 'progress',
        fields: { fecha: NOW, angulo: 'arriba' } as never,
        bytes: jpeg(10, 10),
        declaredMime: 'image/jpeg',
      }),
    );
    expect(err).toMatchObject({ status: 400, code: 'VALIDATION_FAILED' });
    expect(storage.putPrivate).not.toHaveBeenCalled();
  });

  it('accepts no client object key: unknown fields are rejected', async () => {
    const service = createMediaService({ db: untouchableDb, storage: fakeStorage(), now: () => NOW });
    const err = await expectApiError(
      service.upload('u1', {
        kind: 'exercise',
        fields: { exerciseId: 'e1', key: 'users/other/exercise/x' } as never,
        bytes: jpeg(10, 10),
        declaredMime: 'image/jpeg',
      }),
    );
    expect(err.code).toBe('VALIDATION_FAILED');
  });
});

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('media service (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];
  const newUser = () => {
    const u = `test-media-${randomUUID()}`;
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
      for (const t of ['exercise_photos', 'progress_photos', 'exercises', 'media_deletions', 'sync_changes']) {
        await db.execute(sql`DELETE FROM ${sql.identifier(t)} WHERE user_id IN (${list})`);
      }
    }
    await pool.end();
  });

  const setup = (now = NOW) => {
    const storage = fakeStorage();
    let clock = now;
    const service = createMediaService({ db, storage, now: () => clock });
    return { storage, service, setNow: (t: number) => (clock = t) };
  };

  const row = async (table: string, id: string) =>
    (await db.execute(sql`SELECT * FROM ${sql.identifier(table)} WHERE id = ${id}`)).rows[0] as
      | Record<string, unknown>
      | undefined;
  const journal = async (userId: string) =>
    (await db.execute(sql`SELECT * FROM sync_changes WHERE user_id = ${userId} ORDER BY sequence`)).rows;
  const pending = async (key: string) =>
    (await db.execute(sql`SELECT * FROM media_deletions WHERE object_key = ${key}`)).rows[0] as
      | Record<string, unknown>
      | undefined;

  it('uploads a private object under the owner namespace and journals the row', async () => {
    const userId = newUser();
    const { storage, service } = setup();
    const id = `ph-${randomUUID()}`;
    const res = await service.upload(userId, {
      kind: 'exercise',
      fields: { id, exerciseId: 'seed-press-banca' },
      bytes: jpeg(1200, 900),
      declaredMime: 'image/jpeg',
    });

    expect(MediaUploadResponseSchema.parse(res)).toEqual(res);
    expect(res.mediaId).toBe(id);
    expect(res.objectKey).toMatch(new RegExp(`^users/${userHash(userId)}/exercise/[0-9a-f-]{36}$`));
    expect(res.readUrlExpiresAt).toBe(NOW + MEDIA_LIMITS.readUrlTtlSeconds * 1000);
    expect(storage.signRead).toHaveBeenCalledWith(res.objectKey, MEDIA_LIMITS.readUrlTtlSeconds);
    expect(storage.objects.get(res.objectKey)?.contentType).toBe('image/jpeg');

    const stored = await row('exercise_photos', id);
    const changes = await journal(userId);
    expect(changes).toHaveLength(1);
    expect(changes[0]).toMatchObject({ table_name: 'exercisePhotos', record_id: id });
    expect(stored).toMatchObject({
      user_id: userId,
      exercise_id: 'seed-press-banca',
      key: res.objectKey,
      url: `/api/media/read/${id}`,
      mime: 'image/jpeg',
      bytes: jpeg(1200, 900).byteLength,
      width: 1200,
      height: 900,
      deleted_at: null,
      server_version: changes[0].sequence,
    });
    // La URL firmada es transporte: nunca se guarda.
    expect(JSON.stringify(stored)).not.toContain('X-Amz');
  });

  it('generates the media id when the client sends none and stores progress metadata', async () => {
    const userId = newUser();
    const { service } = setup();
    const res = await service.upload(userId, {
      kind: 'progress',
      fields: { fecha: NOW - 1000, angulo: 'lado', nota: 'semana 3' },
      bytes: webp(600, 800),
      declaredMime: 'image/webp',
    });
    expect(res.objectKey.startsWith(`users/${userHash(userId)}/progress/`)).toBe(true);
    expect(await row('progress_photos', res.mediaId)).toMatchObject({
      user_id: userId, angulo: 'lado', nota: 'semana 3', fecha: String(NOW - 1000), mime: 'image/webp',
    });
  });

  it('replays an upload retry for the same id without storing a second object', async () => {
    const userId = newUser();
    const { storage, service } = setup();
    const id = `ph-${randomUUID()}`;
    const input = { kind: 'exercise' as const, fields: { id, exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' };
    const first = await service.upload(userId, input);
    const second = await service.upload(userId, input);
    expect(second.objectKey).toBe(first.objectKey);
    expect(storage.putPrivate).toHaveBeenCalledTimes(1);
    expect(await journal(userId)).toHaveLength(1);
  });

  it('refuses an upload id owned by another user without overwriting it', async () => {
    const owner = newUser();
    const intruder = newUser();
    const { storage, service } = setup();
    const id = `ph-${randomUUID()}`;
    await service.upload(owner, { kind: 'exercise', fields: { id, exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' });
    const err = await expectApiError(
      service.upload(intruder, { kind: 'exercise', fields: { id, exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' }),
    );
    expect(err).toMatchObject({ status: 409, code: 'RECORD_ID_CONFLICT' });
    expect(storage.putPrivate).toHaveBeenCalledTimes(1);
    expect(await row('exercise_photos', id)).toMatchObject({ user_id: owner });
  });

  it('refuses an upload id already used in the other photo table', async () => {
    const userId = newUser();
    const { storage, service } = setup();
    const id = `ph-${randomUUID()}`;
    await service.upload(userId, { kind: 'progress', fields: { id, fecha: NOW, angulo: 'frente' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' });
    const err = await expectApiError(
      service.upload(userId, { kind: 'exercise', fields: { id, exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' }),
    );
    expect(err).toMatchObject({ status: 409, code: 'RECORD_ID_CONFLICT' });
    expect(storage.putPrivate).toHaveBeenCalledTimes(1);
    expect(await row('exercise_photos', id)).toBeUndefined();
  });

  it("refuses to attach a photo to another user's exercise", async () => {
    const owner = newUser();
    const intruder = newUser();
    const exerciseId = `ex-${randomUUID()}`;
    await db.execute(sql`INSERT INTO exercises (id, user_id, updated_at, server_updated_at, nombre, grupo_muscular, equipamiento, tipo, es_personalizado)
      VALUES (${exerciseId}, ${owner}, ${NOW}, ${NOW}, 'X', 'pecho', 'barra', 'fuerza', true)`);
    const { storage, service } = setup();
    const err = await expectApiError(
      service.upload(intruder, { kind: 'exercise', fields: { exerciseId }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' }),
    );
    expect(err).toMatchObject({ status: 400, code: 'INVALID_REFERENCE' });
    expect(storage.putPrivate).not.toHaveBeenCalled();
  });

  it('removes the stored object when the database write fails after upload', async () => {
    const userId = newUser();
    const { storage } = setup();
    const failingDb = new Proxy(db, {
      get(target, prop, receiver) {
        if (prop === 'transaction') return () => Promise.reject(new Error('db down'));
        return Reflect.get(target, prop, receiver);
      },
    });
    const service = createMediaService({ db: failingDb, storage, now: () => NOW });
    await expect(
      service.upload(userId, { kind: 'exercise', fields: { exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' }),
    ).rejects.toThrow('db down');
    expect(storage.putPrivate).toHaveBeenCalledTimes(1);
    expect(storage.objects.size).toBe(0);
  });

  it('maps storage failures to a retryable error', async () => {
    const userId = newUser();
    const { storage, service } = setup();
    vi.mocked(storage.putPrivate).mockRejectedValueOnce(new Error('network'));
    const err = await expectApiError(
      service.upload(userId, { kind: 'exercise', fields: { exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' }),
    );
    expect(err).toMatchObject({ status: 503, code: 'MEDIA_STORAGE_UNAVAILABLE', retryable: true });
  });

  it('grants a fresh short-lived read URL to the owner only', async () => {
    const owner = newUser();
    const other = newUser();
    const { service, setNow } = setup();
    const up = await service.upload(owner, { kind: 'progress', fields: { fecha: NOW, angulo: 'frente' }, bytes: png(20, 20), declaredMime: 'image/png' });

    setNow(NOW + 60_000);
    const grant = await service.readGrant(owner, up.mediaId);
    expect(MediaReadGrantSchema.parse(grant)).toEqual({
      mediaId: up.mediaId,
      readUrl: `https://r2.test/${up.objectKey}?X-Amz-Expires=300`,
      expiresAt: NOW + 60_000 + 300_000,
    });

    const err = await expectApiError(service.readGrant(other, up.mediaId));
    const missing = await expectApiError(service.readGrant(other, `nope-${randomUUID()}`));
    // Ajeno e inexistente son indistinguibles.
    expect(err).toMatchObject({ status: 404, code: 'MEDIA_NOT_FOUND' });
    expect({ status: missing.status, code: missing.code, message: missing.message }).toEqual({
      status: err.status, code: err.code, message: err.message,
    });
  });

  it('signs legacy PWA keys in the owner legacy namespace', async () => {
    const userId = newUser();
    const id = `legacy-${randomUUID()}`;
    const key = `${userId}/progress/${randomUUID()}.jpg`;
    await db.execute(sql`INSERT INTO progress_photos (id, user_id, updated_at, server_updated_at, url, key, fecha, angulo)
      VALUES (${id}, ${userId}, ${NOW}, ${NOW}, 'https://pub.example/x.jpg', ${key}, ${NOW}, 'frente')`);
    const { service } = setup();
    const grant = await service.readGrant(userId, id);
    expect(grant.readUrl).toContain(key);
    // La fila legacy no se toca.
    expect(await row('progress_photos', id)).toMatchObject({ url: 'https://pub.example/x.jpg' });
  });

  it('never signs a key outside the owner namespace even if a client synced it', async () => {
    const victim = newUser();
    const attacker = newUser();
    const id = `forged-${randomUUID()}`;
    const victimKey = mediaObjectKey(victim, 'exercise', randomUUID());
    await db.execute(sql`INSERT INTO exercise_photos (id, user_id, updated_at, server_updated_at, exercise_id, url, key)
      VALUES (${id}, ${attacker}, ${NOW}, ${NOW}, 'e1', 'x', ${victimKey})`);
    const { storage, service, setNow } = setup();
    expect(await expectApiError(service.readGrant(attacker, id))).toMatchObject({ status: 404 });

    // Borrarla tombstonea la fila pero nunca programa el borrado del objeto ajeno.
    await service.delete(attacker, id);
    expect(await pending(victimKey)).toBeUndefined();
    setNow(NOW + MEDIA_DELETION_DELAY_MS + 1);
    await service.processDeletions();
    expect(storage.remove).not.toHaveBeenCalledWith(victimKey);
  });

  it('tombstones on delete, journals it once and treats a duplicate delete as success', async () => {
    const userId = newUser();
    const { service, setNow } = setup();
    const up = await service.upload(userId, { kind: 'exercise', fields: { exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' });

    setNow(NOW + 1000);
    expect(MediaDeleteResponseSchema.parse(await service.delete(userId, up.mediaId))).toEqual({ mediaId: up.mediaId, deleted: true });
    const changes = await journal(userId);
    expect(changes).toHaveLength(2);
    expect(await row('exercise_photos', up.mediaId)).toMatchObject({
      deleted_at: String(NOW + 1000),
      updated_at: String(NOW + 1000),
      server_version: changes[1].sequence,
    });
    expect(await pending(up.objectKey)).toMatchObject({
      user_id: userId,
      due_at: String(NOW + 1000 + MEDIA_DELETION_DELAY_MS),
    });

    setNow(NOW + 2000);
    expect(await service.delete(userId, up.mediaId)).toEqual({ mediaId: up.mediaId, deleted: true });
    expect(await journal(userId)).toHaveLength(2);
    expect(await expectApiError(service.readGrant(userId, up.mediaId))).toMatchObject({ status: 404 });
  });

  it('advances updatedAt past a client clock that ran ahead', async () => {
    const userId = newUser();
    const id = `ahead-${randomUUID()}`;
    const key = mediaObjectKey(userId, 'progress', randomUUID());
    await db.execute(sql`INSERT INTO progress_photos (id, user_id, updated_at, server_updated_at, url, key, fecha, angulo)
      VALUES (${id}, ${userId}, ${NOW + 60_000}, ${NOW}, 'x', ${key}, ${NOW}, 'frente')`);
    const { service } = setup();
    await service.delete(userId, id);
    expect(await row('progress_photos', id)).toMatchObject({ updated_at: String(NOW + 60_001), deleted_at: String(NOW) });
  });

  it("returns not found when deleting another user's media and leaves it intact", async () => {
    const owner = newUser();
    const other = newUser();
    const { service } = setup();
    const up = await service.upload(owner, { kind: 'exercise', fields: { exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' });
    expect(await expectApiError(service.delete(other, up.mediaId))).toMatchObject({ status: 404, code: 'MEDIA_NOT_FOUND' });
    expect(await row('exercise_photos', up.mediaId)).toMatchObject({ deleted_at: null });
    expect(await pending(up.objectKey)).toBeUndefined();
  });

  it('deletes tombstoned objects only after the delay, idempotently', async () => {
    const userId = newUser();
    const { storage, service, setNow } = setup();
    const up = await service.upload(userId, { kind: 'progress', fields: { fecha: NOW, angulo: 'espalda' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' });
    await service.delete(userId, up.mediaId);

    setNow(NOW + MEDIA_DELETION_DELAY_MS - 1);
    await service.processDeletions();
    expect(storage.remove).not.toHaveBeenCalled();

    setNow(NOW + MEDIA_DELETION_DELAY_MS);
    const result = await service.processDeletions();
    expect(result.deleted).toBeGreaterThanOrEqual(1);
    expect(storage.remove).toHaveBeenCalledWith(up.objectKey);
    expect(storage.objects.has(up.objectKey)).toBe(false);
    expect(await pending(up.objectKey)).toBeUndefined();

    await service.processDeletions();
    expect(vi.mocked(storage.remove).mock.calls.filter(([k]) => k === up.objectKey)).toHaveLength(1);
    // La fila sigue como tombstone para que la sync lo propague.
    expect(await row('progress_photos', up.mediaId)).toMatchObject({ deleted_at: String(NOW) });
  });

  it("is not cancelled by another user's live row that copies the key", async () => {
    const owner = newUser();
    const intruder = newUser();
    const { storage, service, setNow } = setup();
    const up = await service.upload(owner, { kind: 'exercise', fields: { exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' });
    await service.delete(owner, up.mediaId);
    // Un cliente ajeno sincroniza una fila viva con la clave del dueño para bloquear el borrado.
    await db.execute(sql`INSERT INTO progress_photos (id, user_id, updated_at, server_updated_at, url, key, fecha, angulo)
      VALUES (${`copy-${randomUUID()}`}, ${intruder}, ${NOW}, ${NOW}, 'x', ${up.objectKey}, ${NOW}, 'frente')`);

    setNow(NOW + MEDIA_DELETION_DELAY_MS);
    await service.processDeletions();
    expect(storage.remove).toHaveBeenCalledWith(up.objectKey);
    expect(await pending(up.objectKey)).toBeUndefined();
  });

  it('cancels the deletion when the row is live again and retries failed deletions later', async () => {
    const userId = newUser();
    const { storage, service, setNow } = setup();
    const revived = await service.upload(userId, { kind: 'exercise', fields: { exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' });
    const flaky = await service.upload(userId, { kind: 'exercise', fields: { exerciseId: 'e1' }, bytes: jpeg(10, 10), declaredMime: 'image/jpeg' });
    await service.delete(userId, revived.mediaId);
    await service.delete(userId, flaky.mediaId);
    // Una escritura LWW posterior resucita la fila: el objeto vuelve a estar en uso.
    await db.execute(sql`UPDATE exercise_photos SET deleted_at = NULL WHERE id = ${revived.mediaId}`);

    const due = NOW + MEDIA_DELETION_DELAY_MS;
    setNow(due);
    vi.mocked(storage.remove).mockImplementation(async (key) => {
      if (key === flaky.objectKey) throw new Error('r2 down');
      storage.objects.delete(key);
    });
    await service.processDeletions();
    expect(storage.remove).not.toHaveBeenCalledWith(revived.objectKey);
    expect(storage.objects.has(revived.objectKey)).toBe(true);
    expect(await pending(revived.objectKey)).toBeUndefined();
    const failed = await pending(flaky.objectKey);
    expect(failed).toMatchObject({ attempts: 1 });
    expect(Number(failed!.due_at)).toBeGreaterThan(due);
    expect(failed!.last_error).toContain('r2 down');

    vi.mocked(storage.remove).mockImplementation(async (key) => {
      storage.objects.delete(key);
    });
    setNow(Number(failed!.due_at));
    await service.processDeletions();
    expect(await pending(flaky.objectKey)).toBeUndefined();
    expect(storage.objects.has(flaky.objectKey)).toBe(false);
  });
});
