// @vitest-environment node
// Phase 5 end-to-end evidence: the REAL route handlers + REAL Postgres (disposable
// SYNC_TEST_DATABASE_URL, migrations 0001-0003) with a fake R2 storage and fake model providers.
// Never touches production, R2 or DeepSeek. Skipped when SYNC_TEST_DATABASE_URL is not set.
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { sql } from 'drizzle-orm';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const url = process.env.SYNC_TEST_DATABASE_URL;
const h = vi.hoisted(() => {
  // The production pool (getPoolDb) reads DATABASE_URL lazily: point it at the disposable DB.
  if (process.env.SYNC_TEST_DATABASE_URL) process.env.DATABASE_URL = process.env.SYNC_TEST_DATABASE_URL;
  return {
    media: undefined as unknown,
    coachDeps: undefined as unknown,
    mesoDeps: undefined as unknown,
    reportDeps: undefined as unknown,
  };
});
vi.mock('@/lib/api/v1/media/runtime', () => ({ getMediaService: () => h.media }));
vi.mock('@/lib/api/v1/ai/runtime', () => ({
  getCoachDeps: () => h.coachDeps,
  getMesocycleDeps: () => h.mesoDeps,
  getReportDeps: () => h.reportDeps,
}));
vi.mock('@/lib/coach-model', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/coach-model')>()),
  deepseekConfigured: () => true,
}));

import type { PoolDb } from '@/db/pool';
import { setAuthSourceForTests } from '@/lib/api/v1/auth';
import { ApiErrorBodySchema } from '@/lib/api/v1/contracts/common';
import {
  AiStreamEventSchema,
  IntelligentReportResponseSchema,
  MesocycleResponseSchema,
  type AiStreamEvent,
  type CoachSnapshotDto,
} from '@/lib/api/v1/ai/contracts';
import { MEDIA_LIMITS, MediaReadGrantSchema, MediaUploadResponseSchema } from '@/lib/api/v1/media/contracts';
import { MEDIA_DELETION_DELAY_MS, createMediaService, type MediaStorage } from '@/lib/api/v1/media/service';
import {
  claimAiResponse,
  claimCoachResponse,
  completeCoachResponse,
  findAiReport,
  loadCoachContext,
  releaseCoachResponse,
  storeAiReport,
} from '@/lib/api/v1/ai/store';
import type { AiObjectProvider, CoachStreamProvider } from '@/lib/coach-model';
import { reportFingerprint } from '@/lib/intelligent-report';

import { POST as postProgress } from '../media/progress/route';
import { POST as postExercise } from '../media/exercises/route';
import { DELETE as deleteProgress } from '../media/progress/[id]/route';
import { GET as getGrant } from '../media/read/[id]/route';
import { POST as postCoach } from '../ai/coach/route';
import { POST as postMesocycle } from '../ai/mesocycles/route';
import { POST as postReport } from '../ai/reports/route';

const NOW = 1_780_000_000_000;
const u16be = (n: number) => [(n >> 8) & 0xff, n & 0xff];
const ascii = (s: string) => [...s].map((c) => c.charCodeAt(0));
function jpeg(width: number, height: number): Uint8Array {
  const app0 = [0xff, 0xe0, ...u16be(16), ...ascii('JFIF'), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0];
  const sof0 = [0xff, 0xc0, ...u16be(17), 8, ...u16be(height), ...u16be(width), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1];
  return new Uint8Array([0xff, 0xd8, ...app0, ...sof0, 0xff, 0xd9]);
}

function fakeStorage() {
  const objects = new Map<string, { bytes: Uint8Array; contentType: string }>();
  const storage: MediaStorage & { objects: typeof objects } = {
    objects,
    putPrivate: async (key, bytes, contentType) => void objects.set(key, { bytes, contentType }),
    signRead: async (key, ttl) => `https://r2.test/${key}?X-Amz-Expires=${ttl}&X-Amz-Signature=fake`,
    remove: async (key) => void objects.delete(key),
  };
  return storage;
}

const errorOf = async (res: Response) => ApiErrorBodySchema.parse(await res.json()).error;
const asUser = (userId: string | null) => setAuthSourceForTests(async () => ({ userId }));

describe.skipIf(!url)('phase 5 media + AI end-to-end (real routes, real DB, fake R2/model)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  let clock = NOW;
  let storage: ReturnType<typeof fakeStorage>;
  let service: ReturnType<typeof createMediaService>;
  const users: string[] = [];
  const newUser = () => {
    const u = `test-e2e-${randomUUID()}`;
    users.push(u);
    return u;
  };
  const seedPro = (userId: string) =>
    execFileSync('node', ['scripts/seed-test-entitlement.mjs', '--user', userId, '--days', '30'], {
      env: { ...process.env, MIGRATION_DATABASE_URL: url!, NODE_ENV: 'test' },
      stdio: 'pipe',
    });
  const count = async (table: string, userId: string) =>
    Number((await db.execute(sql`SELECT count(*) AS n FROM ${sql.identifier(table)} WHERE user_id = ${userId}`)).rows[0].n);

  beforeAll(async () => {
    const { drizzle } = await import('drizzle-orm/node-postgres');
    const { Pool } = await import('pg');
    const schema = await import('@/db/schema');
    pool = new Pool({ connectionString: url, max: 4 });
    db = drizzle(pool, { schema });
  });

  afterAll(async () => {
    setAuthSourceForTests(null);
    if (!db) return;
    if (users.length) {
      const list = sql.join(users.map((u) => sql`${u}`), sql`, `);
      for (const t of [
        'progress_photos', 'exercise_photos', 'exercises', 'media_deletions', 'sync_changes',
        'subscription_entitlements', 'ai_reports', 'ai_responses',
      ]) {
        await db.execute(sql`DELETE FROM ${sql.identifier(t)} WHERE user_id IN (${list})`);
      }
    }
    await pool.end();
  });

  beforeEach(() => {
    clock = NOW;
    storage = fakeStorage();
    service = createMediaService({ db, storage, now: () => clock });
    h.media = service;
  });

  // ── Media ──

  describe('private media', () => {
    const form = (fields: Record<string, string>, bytes = jpeg(800, 600)) => {
      const fd = new FormData();
      for (const [k, v] of Object.entries(fields)) fd.append(k, v);
      fd.append('file', new Blob([bytes as BlobPart], { type: 'image/jpeg' }), 'p.jpg');
      return fd;
    };
    const upload = (path: 'progress' | 'exercises', fd: FormData) =>
      (path === 'progress' ? postProgress : postExercise)(
        new Request(`http://x/api/v1/media/${path}`, { method: 'POST', body: fd }),
      );
    const readReq = (id: string) => new Request(`http://x/api/v1/media/read/${id}`);
    const delReq = (id: string) => new Request(`http://x/api/v1/media/progress/${id}`, { method: 'DELETE' });

    it('upload → short read grant → second user gets 404 on read and delete → delete queues object removal', async () => {
      const owner = newUser();
      const intruder = newUser();
      asUser(owner);

      const id = `ph-${randomUUID()}`;
      const up = await upload('progress', form({ id, fecha: String(NOW - 1000), angulo: 'frente' }));
      expect(up.status).toBe(201);
      const uploaded = MediaUploadResponseSchema.parse(await up.json());
      expect(uploaded.mediaId).toBe(id);
      expect(uploaded.objectKey).toMatch(/^users\/[0-9a-f]{32}\/progress\/[0-9a-f-]{36}$/);
      expect(storage.objects.get(uploaded.objectKey)?.contentType).toBe('image/jpeg');
      // Stored row keeps the object key and the authenticated app route, never a signed/public URL.
      const stored = (await db.execute(sql`SELECT url, key FROM progress_photos WHERE id = ${id}`)).rows[0] as { url: string; key: string };
      expect(stored).toEqual({ url: `/api/media/read/${id}`, key: uploaded.objectKey });

      // Read grant: short-lived signed URL, never cached.
      clock = NOW + 60_000;
      const grantRes = await getGrant(readReq(id));
      expect(grantRes.status).toBe(200);
      expect(grantRes.headers.get('cache-control')).toBe('no-store');
      const grant = MediaReadGrantSchema.parse(await grantRes.json());
      expect(grant.expiresAt - clock).toBe(MEDIA_LIMITS.readUrlTtlSeconds * 1000);
      expect(MEDIA_LIMITS.readUrlTtlSeconds).toBeLessThanOrEqual(300);
      expect(grant.readUrl).toContain(`X-Amz-Expires=${MEDIA_LIMITS.readUrlTtlSeconds}`);

      // Second user: cannot read or delete (404, indistinguishable from unknown); nothing changes.
      asUser(intruder);
      const readOther = await getGrant(readReq(id));
      const readMissing = await getGrant(readReq(`nope-${randomUUID()}`));
      expect(readOther.status).toBe(404);
      expect(await errorOf(readOther)).toMatchObject({ code: 'MEDIA_NOT_FOUND' });
      expect(readMissing.status).toBe(404);
      const delOther = await deleteProgress(delReq(id));
      expect(delOther.status).toBe(404);
      expect((await db.execute(sql`SELECT deleted_at FROM progress_photos WHERE id = ${id}`)).rows[0]).toEqual({ deleted_at: null });
      expect(storage.objects.has(uploaded.objectKey)).toBe(true);

      // Owner deletes: tombstone + deferred, idempotent object removal.
      asUser(owner);
      const del = await deleteProgress(delReq(id));
      expect(del.status).toBe(200);
      expect((await db.execute(sql`SELECT deleted_at FROM progress_photos WHERE id = ${id}`)).rows[0]).not.toEqual({ deleted_at: null });
      const queued = (await db.execute(sql`SELECT object_key FROM media_deletions WHERE object_key = ${uploaded.objectKey}`)).rows;
      expect(queued).toHaveLength(1);
      expect((await getGrant(readReq(id))).status).toBe(404);

      // Nothing is removed before the delay; after it the object is deleted from storage.
      expect(await service.processDeletions()).toMatchObject({ deleted: 0 });
      expect(storage.objects.has(uploaded.objectKey)).toBe(true);
      clock = NOW + 60_000 + MEDIA_DELETION_DELAY_MS + 1000;
      const done = await service.processDeletions();
      expect(done.deleted).toBeGreaterThanOrEqual(1);
      expect(storage.objects.has(uploaded.objectKey)).toBe(false);
    });

    it('an exercise photo requires the caller own a custom exercise; a retry with the same id stores one object', async () => {
      const owner = newUser();
      const other = newUser();
      const exerciseId = `ex-${randomUUID()}`;
      await db.execute(sql`INSERT INTO exercises (id, user_id, updated_at, server_updated_at, nombre, grupo_muscular, equipamiento, tipo, es_personalizado)
        VALUES (${exerciseId}, ${owner}, ${NOW}, ${NOW}, 'Propio', 'pecho', 'barra', 'fuerza', true)`);
      const id = `ph-${randomUUID()}`;
      asUser(other);
      expect((await upload('exercises', form({ id: `ph-${randomUUID()}`, exerciseId }))).status).toBe(400);
      asUser(owner);
      const first = await upload('exercises', form({ id, exerciseId }));
      const second = await upload('exercises', form({ id, exerciseId }));
      expect([first.status, second.status]).toEqual([201, 201]);
      expect(storage.objects.size).toBe(1);
      expect(await count('exercise_photos', owner)).toBe(1);
    });
  });

  // ── AI ──

  const snapshot = (over: Partial<CoachSnapshotDto> = {}): CoachSnapshotDto => ({
    estancados: [{ ejercicio: 'Press banca', sesionesSinMejora: 4 }],
    semana: { sesiones: 3, objetivo: 3, volumen: 9000, deltaPct: 5, prs: [{ ejercicio: 'Sentadilla', tipo: 'peso' }] },
    grupos: [{ grupo: 'Pecho', volumenSemana: 4000, diasSinEntrenar: 2, objetivo: 12 }],
    cuerpo: { peso: { actual: 80, delta4sem: -1 }, medidas: [] },
    ...over,
  });
  const json = (path: string, payload: unknown) =>
    new Request(`http://x/api/v1/ai/${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    });

  const coachBody = (cmid: string, text = '¿Cómo rompo el estancamiento en press banca?') => ({
    clientMessageId: cmid, text, snapshot: snapshot(),
  });
  const mesoBody = (crid: string) => ({
    clientRequestId: crid,
    params: { objetivo: 'hipertrofia', diasPorSemana: 2, semanas: 4, minutosPorSesion: 60 },
    snapshot: snapshot(),
    catalogo: [
      { nombre: 'Press banca', grupo: 'pecho', equipamiento: 'barra' },
      { nombre: 'Sentadilla', grupo: 'cuadriceps', equipamiento: 'barra' },
      { nombre: 'Remo con barra', grupo: 'espalda', equipamiento: 'barra' },
    ],
  });
  const ex = (nombre: string) => ({
    nombre, grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto',
    seriesObjetivo: 4, repsObjetivo: 8, descansoSegundos: 120, nuevo: false,
  });
  const mesoOutput = () => ({
    nombre: 'Bloque hipertrofia', objetivo: 'hipertrofia', semanas: 4, diasPorSemana: 2, notas: 'Sube carga.',
    progresion: [
      { semana: 1, descarga: false, ajuste: 'Base' }, { semana: 2, descarga: false, ajuste: '+1 serie' },
      { semana: 3, descarga: false, ajuste: '+2,5 kg' }, { semana: 4, descarga: true, ajuste: 'Descarga' },
    ],
    dias: [
      { nombre: 'Torso', orden: 1, ejercicios: [ex('Press banca'), ex('Remo con barra')] },
      { nombre: 'Pierna', orden: 2, ejercicios: [ex('Sentadilla')] },
    ],
  });
  const reportOutput = () => ({
    resumen: 'Buena semana.', puntosFuertes: ['Constancia'], aMejorar: ['Press banca estancado'],
    recomendaciones: ['Cambia el rango de reps'],
  });

  /** Production wiring (real Postgres stores) with fake model providers. */
  function wire(coach: CoachStreamProvider, object: AiObjectProvider, timeoutMs?: number) {
    h.coachDeps = {
      provider: coach,
      timeoutMs,
      store: {
        claim: (u: string, c: string, i: never) => claimCoachResponse(db, u, c, i),
        loadContext: (u: string, n: number, m: number, e: string) => loadCoachContext(db, u, n, m, e),
        complete: (u: string, r: string, a: string, c: string, n: number) => completeCoachResponse(db, u, r, a, c, n),
        release: (u: string, r: string, a: string, n: number) => releaseCoachResponse(db, u, r, a, n),
      },
    };
    h.mesoDeps = {
      provider: object,
      store: {
        claim: (u: string, c: string, i: never) => claimAiResponse(db, u, c, 'mesocycle', i),
        complete: (u: string, r: string, a: string, c: string, n: number) => completeCoachResponse(db, u, r, a, c, n),
        release: (u: string, r: string, a: string, n: number) => releaseCoachResponse(db, u, r, a, n),
      },
    };
    h.reportDeps = {
      provider: object,
      store: {
        find: (u: string, k: string, f: string) => findAiReport(db, u, k, f),
        store: (u: string, k: string, f: string, i: never) => storeAiReport(db, u, k, f, i),
      },
    };
  }
  async function events(res: Response): Promise<AiStreamEvent[]> {
    const raw = await res.text();
    expect(res.status, raw).toBe(200);
    return raw.split('\n').filter(Boolean).map((l) => AiStreamEventSchema.parse(JSON.parse(l)));
  }
  const text = (evs: AiStreamEvent[]) => evs.map((e) => (e.type === 'delta' ? e.text : '')).join('');

  describe('Free user', () => {
    it('gets 403 PRO_REQUIRED from coach, mesocycles and reports regardless of payload; no model call, no rows', async () => {
      const free = newUser();
      asUser(free);
      const coachCalls = vi.fn();
      const objectCalls = vi.fn();
      wire(
        { async *stream() { coachCalls(); yield 'x'; } },
        { async generate() { objectCalls(); return {}; } },
      );
      const payloads: unknown[] = [undefined, {}, 'not json', coachBody(randomUUID()), mesoBody(randomUUID()), { snapshot: snapshot() }];
      for (const [route, post] of [
        ['coach', postCoach], ['mesocycles', postMesocycle], ['reports', postReport],
      ] as const) {
        for (const p of payloads) {
          const res = await post(new Request(`http://x/api/v1/ai/${route}`, {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: p === undefined ? undefined : typeof p === 'string' ? p : JSON.stringify(p),
          }));
          expect(res.status, `${route} ${JSON.stringify(p)?.slice(0, 30)}`).toBe(403);
          expect(await errorOf(res)).toMatchObject({ code: 'PRO_REQUIRED', retryable: false });
        }
      }
      expect(coachCalls).not.toHaveBeenCalled();
      expect(objectCalls).not.toHaveBeenCalled();
      expect(await count('ai_responses', free)).toBe(0);
      expect(await count('ai_reports', free)).toBe(0);
    });

    it('an expired or non-granting test entitlement is still Free', async () => {
      const user = newUser();
      seedPro(user);
      await db.execute(sql`UPDATE subscription_entitlements SET state = 'EXPIRED' WHERE user_id = ${user}`);
      asUser(user);
      wire({ async *stream() { yield 'x'; } }, { generate: async () => ({}) });
      expect((await postReport(json('reports', { snapshot: snapshot() }))).status).toBe(403);
    });
  });

  describe('Pro user (seeded test entitlement)', () => {
    it('Coach: streams, survives an interruption, and a retry of the same clientMessageId replays without a new model call', async () => {
      const pro = newUser();
      seedPro(pro);
      asUser(pro);
      const cmid = randomUUID();
      let calls = 0;
      let failNext = true;
      wire(
        {
          async *stream() {
            calls++;
            yield 'Sube ';
            if (failNext) throw new Error('upstream 500');
            yield '2,5 kg cada semana.';
          },
        },
        { generate: async () => ({}) },
      );

      // Interrupted stream: partial delta, then retryable error; the row stays incomplete.
      const first = await events(await postCoach(json('coach', coachBody(cmid))));
      expect(first.map((e) => e.type)).toEqual(['start', 'delta', 'error']);
      expect(first.at(-1)).toEqual({ type: 'error', code: 'AI_PROVIDER_ERROR', retryable: true });
      const row = () => db.execute(sql`SELECT id, status, content FROM ai_responses WHERE user_id = ${pro}`);
      expect((await row()).rows).toHaveLength(1);
      expect((await row()).rows[0]).toMatchObject({ status: 'incomplete' });
      const responseId = (first[0] as { responseId: string }).responseId;

      // Retry with the same id completes the SAME response (no duplicate row).
      failNext = false;
      const retry = await events(await postCoach(json('coach', coachBody(cmid))));
      expect(retry[0]).toEqual({ type: 'start', clientMessageId: cmid, responseId });
      expect(retry.at(-1)).toEqual({ type: 'complete', responseId, disclaimer: true });
      expect(text(retry)).toBe('Sube 2,5 kg cada semana.');
      expect(calls).toBe(2);
      expect((await row()).rows).toHaveLength(1);
      expect((await row()).rows[0]).toMatchObject({ status: 'complete', content: 'Sube 2,5 kg cada semana.' });

      // Third retry: pure replay, zero model calls.
      const replay = await events(await postCoach(json('coach', coachBody(cmid))));
      expect(text(replay)).toBe('Sube 2,5 kg cada semana.');
      expect(replay[0]).toMatchObject({ responseId });
      expect(calls).toBe(2);
    });

    it('Coach: a client disconnect mid-stream leaves the response incomplete and retryable at once', async () => {
      const pro = newUser();
      seedPro(pro);
      asUser(pro);
      const cmid = randomUUID();
      let signal: AbortSignal | undefined;
      wire(
        {
          async *stream(input) {
            signal = input.abortSignal;
            yield 'Uno ';
            await new Promise(() => {});
          },
        },
        { generate: async () => ({}) },
      );
      const controller = new AbortController();
      const res = await postCoach(new Request('http://x/api/v1/ai/coach', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(coachBody(cmid)),
        signal: controller.signal,
      }));
      const reader = res.body!.getReader();
      await reader.read();
      await reader.read();
      await reader.cancel();
      await vi.waitFor(() => expect(signal?.aborted).toBe(true));
      await vi.waitFor(async () => {
        const r = (await db.execute(sql`SELECT status, lease_expires_at FROM ai_responses WHERE user_id = ${pro}`)).rows[0] as Record<string, unknown>;
        expect(r.status).toBe('incomplete');
        expect(r.lease_expires_at).toBeNull();
      });
    });

    it('mesocycle: returns a validated draft, writes no user data, and an idempotent retry replays it without a model call', async () => {
      const pro = newUser();
      seedPro(pro);
      asUser(pro);
      const crid = randomUUID();
      const generate = vi.fn(async () => mesoOutput());
      wire({ async *stream() {} }, { generate });
      const syncBefore = await count('sync_changes', pro);

      const first = MesocycleResponseSchema.parse(await (await postMesocycle(json('mesocycles', mesoBody(crid)))).json());
      expect(first).toMatchObject({ clientRequestId: crid, disclaimer: true, omitidos: [] });
      expect(first.draft.dias.map((d) => d.nombre)).toEqual(['Torso', 'Pierna']);
      const again = MesocycleResponseSchema.parse(await (await postMesocycle(json('mesocycles', mesoBody(crid)))).json());
      expect(again).toEqual(first);
      expect(generate).toHaveBeenCalledTimes(1);
      expect(await count('sync_changes', pro)).toBe(syncBefore);
      expect(await count('mesocycles', pro)).toBe(0);

      // Same id, different parameters: refused, no second model call.
      const reused = await postMesocycle(json('mesocycles', { ...mesoBody(crid), params: { ...mesoBody(crid).params, semanas: 5 } }));
      expect(reused.status).toBeGreaterThanOrEqual(400);
      expect(generate).toHaveBeenCalledTimes(1);
    });

    it('report: fingerprint cache hit skips the model; a changed source regenerates and invalidates the old report', async () => {
      const pro = newUser();
      seedPro(pro);
      asUser(pro);
      const generate = vi.fn(async () => reportOutput());
      wire({ async *stream() {} }, { generate });
      const post = async (s: CoachSnapshotDto) =>
        IntelligentReportResponseSchema.parse(await (await postReport(json('reports', { snapshot: s }))).json());
      const reports = async () => (await db.execute(sql`SELECT source_fingerprint AS fingerprint FROM ai_reports WHERE user_id = ${pro}`)).rows;

      const a = await post(snapshot());
      expect(a).toMatchObject({ cached: false, disclaimer: true });
      const hit = await post(snapshot());
      expect(hit).toMatchObject({ cached: true, reportId: a.reportId, generatedAt: a.generatedAt });
      expect(generate).toHaveBeenCalledTimes(1);

      const changed = snapshot({ semana: { ...snapshot().semana, sesiones: 4 } });
      const b = await post(changed);
      expect(b).toMatchObject({ cached: false });
      expect(b.reportId).not.toBe(a.reportId);
      expect(generate).toHaveBeenCalledTimes(2);
      expect(await reports()).toEqual([{ fingerprint: reportFingerprint(changed) }]);

      // Back to the old source: the old report was invalidated, so it regenerates.
      await post(snapshot());
      expect(generate).toHaveBeenCalledTimes(3);
    });

    it('Pro granted to one user does not unlock another', async () => {
      const pro = newUser();
      const free = newUser();
      seedPro(pro);
      wire({ async *stream() {} }, { generate: async () => reportOutput() });
      asUser(pro);
      expect((await postReport(json('reports', { snapshot: snapshot() }))).status).toBe(200);
      asUser(free);
      expect((await postReport(json('reports', { snapshot: snapshot() }))).status).toBe(403);
    });
  });
});
