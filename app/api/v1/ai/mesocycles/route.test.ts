// @vitest-environment node
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { sql } from 'drizzle-orm';
import type { PoolDb } from '@/db/pool';
import { setAuthSourceForTests } from '@/lib/api/v1/auth';
import { RateLimitedError } from '@/lib/api/v1/errors';
import { ApiErrorBodySchema } from '@/lib/api/v1/contracts/common';
import { MesocycleResponseSchema, type CoachSnapshotDto, type MesocycleRequest } from '@/lib/api/v1/ai/contracts';
import { responseInProgress, type CoachClaimInput, type CoachResponseClaim } from '@/lib/api/v1/ai/store';
import { AiOutputError, type AiObjectInput, type AiObjectProvider } from '@/lib/coach-model';
import { MEDICAL_DISCLAIMER_INSTRUCTION } from '@/lib/coach-prompt';
import type { MesocycleDeps, MesocycleStore } from '@/lib/mesocycle-draft';
import { MESO_SCHEMA } from '@/lib/meso-prompt';

const h = vi.hoisted(() => ({
  deps: undefined as unknown as MesocycleDeps,
  isPro: vi.fn<(...args: unknown[]) => Promise<boolean>>(async () => true),
  configured: vi.fn(() => true),
  rate: vi.fn<(...args: unknown[]) => Promise<void>>(async () => {}),
}));
vi.mock('@/lib/api/v1/rate-limit', () => ({ enforceRateLimit: (...a: unknown[]) => h.rate(...a) }));
vi.mock('@/lib/entitlements/reader', () => ({
  createDbEntitlementReader: () => ({ isPro: (...a: unknown[]) => h.isPro(...a) }),
}));
vi.mock('@/lib/coach-model', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/coach-model')>()),
  deepseekConfigured: () => h.configured(),
}));
vi.mock('@/lib/api/v1/ai/runtime', () => ({ getMesocycleDeps: () => h.deps }));

import { POST } from './route';

const USER = 'user_1';
const CRID = '1b4e28ba-2fa1-4d2b-883f-0016d3cca427';
const RID = '9a7e1f7c-0d5b-4b3e-8a51-3d9b1f0c6e22';
const NOW = 1_767_225_600_000;

const snapshot = (): CoachSnapshotDto => ({
  estancados: [{ ejercicio: 'Press banca', sesionesSinMejora: 4 }],
  semana: { sesiones: 2, objetivo: 3, volumen: 1235, deltaPct: null, prs: [] },
  grupos: [],
  cuerpo: { peso: null, medidas: [] },
});
const request = (over: Partial<MesocycleRequest> = {}): MesocycleRequest => ({
  clientRequestId: CRID,
  params: { objetivo: 'hipertrofia', diasPorSemana: 2, semanas: 4, minutosPorSesion: 60 },
  snapshot: snapshot(),
  catalogo: [
    { nombre: 'Press banca', grupo: 'pecho', equipamiento: 'barra' },
    { nombre: 'Sentadilla', grupo: 'cuadriceps', equipamiento: 'barra' },
    { nombre: 'Remo con barra', grupo: 'espalda', equipamiento: 'barra' },
  ],
  ...over,
});
const ex = (nombre: string, over: Record<string, unknown> = {}) => ({
  nombre, grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto',
  seriesObjetivo: 4, repsObjetivo: 8, descansoSegundos: 120, nuevo: false, ...over,
});
/** A valid model answer (MESO_SCHEMA shape) for the default request. */
const modelOutput = (over: Record<string, unknown> = {}) => ({
  nombre: 'Bloque hipertrofia',
  objetivo: 'hipertrofia',
  semanas: 4,
  diasPorSemana: 2,
  notas: 'Sube carga cuando completes las reps.',
  progresion: [
    { semana: 1, descarga: false, ajuste: 'Base' },
    { semana: 2, descarga: false, ajuste: '+1 serie' },
    { semana: 3, descarga: false, ajuste: '+2,5 kg' },
    { semana: 4, descarga: true, ajuste: 'Descarga: -40 % volumen' },
  ],
  dias: [
    // Out of order on purpose: the server sorts by `orden`.
    { nombre: 'Pierna', orden: 2, ejercicios: [ex('sentadilla', { repsObjetivo: 6 })] },
    { nombre: 'Torso', orden: 1, ejercicios: [ex('  PRESS   BANCA '), ex('Remo con barra'), ex('Press militar', { nuevo: true })] },
  ],
  ...over,
});

const post = (payload: unknown, init: RequestInit = {}) =>
  POST(new Request('http://x/api/v1/ai/mesocycles', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: typeof payload === 'string' ? payload : JSON.stringify(payload),
    ...init,
  }));
const errorOf = async (res: Response) => ApiErrorBodySchema.parse(await res.json()).error;
const okOf = async (res: Response) => {
  const raw = await res.text();
  expect(res.status, raw).toBe(200);
  return MesocycleResponseSchema.parse(JSON.parse(raw));
};

type Row = { id: string; status: 'complete' | 'incomplete'; content: string | null; attemptId: string | null; lease: number | null };
function memoryStore() {
  const rows = new Map<string, Row>();
  const byId = (id: string) => [...rows.values()].find((r) => r.id === id);
  return {
    rows,
    claim: vi.fn(async (userId: string, crid: string, input: CoachClaimInput): Promise<CoachResponseClaim> => {
      const key = `${userId}|${crid}`;
      const row = rows.get(key);
      if (!row) {
        rows.set(key, { id: input.newId, status: 'incomplete', content: null, attemptId: input.attemptId, lease: input.leaseExpiresAt });
        return { status: 'incomplete', responseId: input.newId, attemptId: input.attemptId };
      }
      if (row.status === 'complete') return { status: 'complete', responseId: row.id, content: row.content! };
      if (row.lease !== null && row.lease > input.now) throw responseInProgress();
      Object.assign(row, { attemptId: input.attemptId, lease: input.leaseExpiresAt });
      return { status: 'incomplete', responseId: row.id, attemptId: input.attemptId };
    }),
    complete: vi.fn(async (_u: string, responseId: string, attemptId: string, content: string) => {
      const row = byId(responseId);
      if (!row || row.status !== 'incomplete' || row.attemptId !== attemptId) return false;
      Object.assign(row, { status: 'complete', content, attemptId: null, lease: null });
      return true;
    }),
    release: vi.fn(async (_u: string, responseId: string, attemptId: string) => {
      const row = byId(responseId);
      if (row && row.status === 'incomplete' && row.attemptId === attemptId) row.lease = null;
    }),
  } satisfies MesocycleStore & { rows: Map<string, Row> };
}

function fakeProvider(answer: () => unknown) {
  const calls: AiObjectInput[] = [];
  const provider: AiObjectProvider = {
    async generate(input) {
      calls.push(input);
      return answer();
    },
  };
  return { provider, calls };
}

let store: ReturnType<typeof memoryStore>;
let warn: MockInstance<typeof console.warn>;
let info: MockInstance<typeof console.info>;
let error: MockInstance<typeof console.error>;
const setDeps = (provider: AiObjectProvider, over: Partial<MesocycleDeps> = {}) => {
  h.deps = { store, provider, newId: () => RID, now: () => NOW, ...over };
};
const logged = () => [...info.mock.calls, ...warn.mock.calls, ...error.mock.calls].flat().map(String).join('\n');

beforeEach(() => {
  setAuthSourceForTests(async () => ({ userId: USER }));
  h.isPro.mockResolvedValue(true);
  h.configured.mockReturnValue(true);
  store = memoryStore();
  warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  info = vi.spyOn(console, 'info').mockImplementation(() => {});
  error = vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  setAuthSourceForTests(null);
  vi.clearAllMocks();
  warn.mockRestore();
  info.mockRestore();
  error.mockRestore();
});

describe('POST /api/v1/ai/mesocycles — authorization and limits (before any model call or write)', () => {
  const untouched = (calls: AiObjectInput[]) => {
    expect(store.claim).not.toHaveBeenCalled();
    expect(calls).toHaveLength(0);
  };

  it('401 without a principal', async () => {
    const { provider, calls } = fakeProvider(modelOutput);
    setDeps(provider);
    setAuthSourceForTests(async () => ({ userId: null }));
    const res = await post(request());
    expect(res.status).toBe(401);
    expect(h.rate).not.toHaveBeenCalled();
    untouched(calls);
  });

  it('429 with Retry-After before the entitlement check', async () => {
    const { provider, calls } = fakeProvider(modelOutput);
    setDeps(provider);
    h.rate.mockRejectedValueOnce(new RateLimitedError(7));
    const res = await post(request());
    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('7');
    expect(h.rate).toHaveBeenCalledWith('ai', USER);
    expect(h.isPro).not.toHaveBeenCalled();
    untouched(calls);
  });

  it('403 PRO_REQUIRED for a non-Pro user; 503 ENTITLEMENT_UNAVAILABLE when the store errors', async () => {
    const { provider, calls } = fakeProvider(modelOutput);
    setDeps(provider);
    h.isPro.mockResolvedValueOnce(false);
    const denied = await post(request());
    expect(denied.status).toBe(403);
    expect(await errorOf(denied)).toMatchObject({ code: 'PRO_REQUIRED', retryable: false });
    h.isPro.mockRejectedValueOnce(new Error('db down'));
    const down = await post(request());
    expect(down.status).toBe(503);
    expect(await errorOf(down)).toMatchObject({ code: 'ENTITLEMENT_UNAVAILABLE', retryable: true });
    untouched(calls);
  });

  it('503 AI_UNAVAILABLE when DeepSeek is not configured', async () => {
    const { provider, calls } = fakeProvider(modelOutput);
    setDeps(provider);
    h.configured.mockReturnValue(false);
    const res = await post(request());
    expect(res.status).toBe(503);
    expect((await errorOf(res)).code).toBe('AI_UNAVAILABLE');
    untouched(calls);
  });

  it('rejects out-of-range params, unknown fields and an oversized catalog with 400', async () => {
    const { provider, calls } = fakeProvider(modelOutput);
    setDeps(provider);
    const params = request().params;
    const many = Array.from({ length: 401 }, (_, i) => ({ nombre: `E${i}`, grupo: 'pecho', equipamiento: 'barra' }));
    for (const bad of [
      { ...request(), params: { ...params, diasPorSemana: 7 } },
      { ...request(), params: { ...params, semanas: 3 } },
      { ...request(), params: { ...params, objetivo: 'perder peso ya' } },
      { ...request(), clientRequestId: 'req-1' },
      { ...request(), userId: 'someone-else' },
      { ...request(), catalogo: [{ id: 'seed-1', nombre: 'Press', grupo: 'pecho', equipamiento: 'barra' }] },
      { ...request(), catalogo: many },
    ]) {
      const res = await post(bad);
      expect(res.status).toBe(400);
      expect((await errorOf(res)).code).toBe('INVALID_REQUEST');
    }
    expect((await post(request(), { headers: { 'content-type': 'text/plain' } })).status).toBe(415);
    expect((await post('x'.repeat(200 * 1024))).status).toBe(413);
    untouched(calls);
  });
});

describe('POST /api/v1/ai/mesocycles — validated draft', () => {
  it('returns a draft whose exercises are the catalog names, days in order, weekly progression 1..n', async () => {
    const { provider, calls } = fakeProvider(modelOutput);
    setDeps(provider);
    const res = await post(request());
    expect(res.headers.get('cache-control')).toBe('no-store');
    const out = await okOf(res);
    expect(out).toMatchObject({ clientRequestId: CRID, responseId: RID, disclaimer: true, omitidos: ['Press militar'] });
    expect(out.draft.dias.map((d) => d.nombre)).toEqual(['Torso', 'Pierna']);
    expect(out.draft.dias[0].ejercicios.map((e) => e.nombre)).toEqual(['Press banca', 'Remo con barra']);
    expect(out.draft.dias[1].ejercicios).toEqual([{ nombre: 'Sentadilla', seriesObjetivo: 4, repsObjetivo: 6, descansoSegundos: 120 }]);
    expect(out.draft.progresion.map((p) => [p.semana, p.descarga])).toEqual([[1, false], [2, false], [3, false], [4, true]]);
    // No ids of any kind in the draft: the client creates every row with its own ids.
    expect(JSON.stringify(out.draft)).not.toMatch(/"id"|exerciseId|routineId/);

    // Structured output with MESO_SCHEMA; catalog-only + medical rules; snapshot in the prompt.
    expect(calls[0].schema).toBe(MESO_SCHEMA);
    expect(calls[0].system).toContain(MEDICAL_DISCLAIMER_INSTRUCTION);
    expect(calls[0].system).toContain('ÚNICAMENTE ejercicios del catálogo');
    expect(calls[0].prompt).toContain('- Remo con barra (espalda, barra)');
    expect(calls[0].prompt).toContain('Press banca');
    expect(store.rows.get(`${USER}|${CRID}`)?.status).toBe('complete');
  });

  it('progression weeks given out of order are sorted', async () => {
    const out = modelOutput();
    (out.progresion as unknown[]).reverse();
    setDeps(fakeProvider(() => out).provider);
    expect((await okOf(await post(request()))).draft.progresion.map((p) => p.semana)).toEqual([1, 2, 3, 4]);
  });

  it.each([
    ['not an object', () => 'texto libre'],
    ['missing dias', () => ({ ...modelOutput(), dias: undefined })],
    ['wrong week count', () => modelOutput({ semanas: 5 })],
    ['progression gap', () => modelOutput({ progresion: modelOutput().progresion.slice(0, 3) })],
    ['wrong day count', () => modelOutput({ dias: modelOutput().dias.slice(0, 1) })],
    ['day left empty after dropping unknown exercises', () =>
      modelOutput({ dias: [{ nombre: 'A', orden: 1, ejercicios: [ex('Inventado')] }, modelOutput().dias[0]] })],
    ['fractional reps', () => modelOutput({ dias: [{ nombre: 'A', orden: 1, ejercicios: [ex('Press banca', { repsObjetivo: 8.5 })] }, modelOutput().dias[0]] })],
    ['absurd sets', () => modelOutput({ dias: [{ nombre: 'A', orden: 1, ejercicios: [ex('Press banca', { seriesObjetivo: 40 })] }, modelOutput().dias[0]] })],
    ['absurd rest', () => modelOutput({ dias: [{ nombre: 'A', orden: 1, ejercicios: [ex('Press banca', { descansoSegundos: 5 })] }, modelOutput().dias[0]] })],
    ['blank name', () => modelOutput({ nombre: '   ' })],
  ])('malformed model output (%s) → 502 AI_BAD_OUTPUT retryable; the attempt is released', async (_name, answer) => {
    setDeps(fakeProvider(answer).provider);
    const res = await post(request());
    expect(res.status).toBe(502);
    expect(await errorOf(res)).toMatchObject({ code: 'AI_BAD_OUTPUT', retryable: true });
    const row = store.rows.get(`${USER}|${CRID}`)!;
    expect(row).toMatchObject({ status: 'incomplete', lease: null });
  });

  it('an unparseable model object (AiOutputError) is AI_BAD_OUTPUT; a provider failure is AI_PROVIDER_ERROR', async () => {
    setDeps({ generate: async () => { throw new AiOutputError(); } });
    expect(await errorOf(await post(request()))).toMatchObject({ code: 'AI_BAD_OUTPUT', retryable: true });
    setDeps({ generate: async () => { throw new Error('upstream 500 with the prompt Press banca'); } });
    const res = await post(request());
    expect(res.status).toBe(502);
    expect(await errorOf(res)).toMatchObject({ code: 'AI_PROVIDER_ERROR', retryable: true });
    expect(logged()).not.toContain('Press banca');
    expect(logged()).toContain(`responseId=${RID}`);
  });

  it('times out a provider that ignores its abort signal: 504 AI_TIMEOUT, attempt released', async () => {
    let aborted = false;
    setDeps(
      { generate: ({ abortSignal }) => { abortSignal.addEventListener('abort', () => { aborted = true; }); return new Promise(() => {}); } },
      { timeoutMs: 20 },
    );
    const res = await post(request());
    expect(res.status).toBe(504);
    expect(await errorOf(res)).toMatchObject({ code: 'AI_TIMEOUT', retryable: true });
    expect(aborted).toBe(true);
    expect(store.rows.get(`${USER}|${CRID}`)?.lease).toBeNull();
  });

  it('retry of the same clientRequestId replays the stored draft without calling the model', async () => {
    const { provider, calls } = fakeProvider(modelOutput);
    setDeps(provider);
    const first = await okOf(await post(request()));
    const again = await okOf(await post(request({ snapshot: { ...snapshot(), estancados: [] } })));
    expect(again).toEqual(first);
    expect(calls).toHaveLength(1);
    expect(store.rows.size).toBe(1);
  });

  it('retry after a failed attempt regenerates under the same responseId (one row, no double charge on success)', async () => {
    let n = 0;
    const { provider, calls } = fakeProvider(() => (++n === 1 ? { bad: true } : modelOutput()));
    let ids = 0;
    setDeps(provider, { newId: () => `${RID.slice(0, -1)}${ids++ % 10}` });
    expect((await post(request())).status).toBe(502);
    const ok = await okOf(await post(request()));
    expect(ok.responseId).toBe(`${RID.slice(0, -1)}0`);
    expect(calls).toHaveLength(2);
    await okOf(await post(request()));
    expect(calls).toHaveLength(2);
    expect(store.rows.size).toBe(1);
  });

  it('409 AI_RESPONSE_IN_PROGRESS while another attempt holds the lease; the model is not called', async () => {
    store.rows.set(`${USER}|${CRID}`, { id: RID, status: 'incomplete', content: null, attemptId: 'other', lease: NOW + 60_000 });
    const { provider, calls } = fakeProvider(modelOutput);
    setDeps(provider);
    const res = await post(request());
    expect(res.status).toBe(409);
    expect(await errorOf(res)).toMatchObject({ code: 'AI_RESPONSE_IN_PROGRESS', retryable: true });
    expect(calls).toHaveLength(0);
  });

  it('an attempt that lost its lease does not report its draft: 409 AI_RESPONSE_CONFLICT', async () => {
    const { provider } = fakeProvider(() => {
      store.rows.get(`${USER}|${CRID}`)!.attemptId = 'newer';
      return modelOutput();
    });
    setDeps(provider);
    const res = await post(request());
    expect(res.status).toBe(409);
    expect(await errorOf(res)).toMatchObject({ code: 'AI_RESPONSE_CONFLICT', retryable: true });
  });

  it('reusing a clientRequestId with other params is 409 CLIENT_MESSAGE_ID_CONFLICT (not retryable)', async () => {
    const { provider, calls } = fakeProvider(modelOutput);
    setDeps(provider);
    await okOf(await post(request()));
    const res = await post(request({ params: { ...request().params, minutosPorSesion: 45 } }));
    expect(res.status).toBe(409);
    expect(await errorOf(res)).toMatchObject({ code: 'CLIENT_MESSAGE_ID_CONFLICT', retryable: false });
    expect(calls).toHaveLength(1);
  });

  it('logs ids and outcomes only, never catalog, snapshot or model text', async () => {
    setDeps(fakeProvider(modelOutput).provider);
    await okOf(await post(request()));
    expect(logged()).toContain('outcome=complete');
    for (const secret of ['Press banca', 'Bloque hipertrofia', 'Sentadilla', 'Descarga']) expect(logged()).not.toContain(secret);
  });
});

// ── Against a disposable Postgres (SYNC_TEST_DATABASE_URL): real ai_responses ──

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('POST /api/v1/ai/mesocycles (integration)', () => {
  let pool: import('pg').Pool;
  let db: PoolDb;
  const users: string[] = [];

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
      await db.execute(sql`DELETE FROM ai_responses WHERE user_id IN (${list})`);
    }
    await pool.end();
  });

  const dbDeps = async (provider: AiObjectProvider): Promise<MesocycleDeps> => {
    const s = await import('@/lib/api/v1/ai/store');
    return {
      provider,
      store: {
        claim: (u, c, input) => s.claimAiResponse(db, u, c, 'mesocycle', input),
        complete: (u, r, a, c, now) => s.completeCoachResponse(db, u, r, a, c, now),
        release: (u, r, a, now) => s.releaseCoachResponse(db, u, r, a, now),
      },
    };
  };
  const newUser = () => {
    const u = `test-meso-${crypto.randomUUID()}`;
    users.push(u);
    setAuthSourceForTests(async () => ({ userId: u }));
    return u;
  };
  const rowsOf = async (userId: string) =>
    (await db.execute(sql`SELECT id, kind, status FROM ai_responses WHERE user_id = ${userId}`)).rows;

  it('stores one mesocycle row and replays it; writes no user data', async () => {
    const u = newUser();
    const crid = crypto.randomUUID();
    const { provider, calls } = fakeProvider(modelOutput);
    h.deps = await dbDeps(provider);
    const first = await okOf(await post(request({ clientRequestId: crid })));
    const again = await okOf(await post(request({ clientRequestId: crid })));
    expect(again).toEqual(first);
    expect(calls).toHaveLength(1);
    expect(await rowsOf(u)).toEqual([{ id: first.responseId, kind: 'mesocycle', status: 'complete' }]);
    for (const t of ['mesocycles', 'routines', 'routine_exercises', 'exercises']) {
      const n = (await db.execute(sql`SELECT count(*) AS n FROM ${sql.identifier(t)} WHERE user_id = ${u}`)).rows[0].n;
      expect(Number(n)).toBe(0);
    }
  });

  it('two concurrent requests with the same clientRequestId call the model once', async () => {
    newUser();
    const crid = crypto.randomUUID();
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    let n = 0;
    h.deps = await dbDeps({ generate: async () => { n++; await gate; return modelOutput(); } });
    const a = post(request({ clientRequestId: crid }));
    await vi.waitFor(() => expect(n).toBe(1));
    const b = await post(request({ clientRequestId: crid }));
    expect(b.status).toBe(409);
    expect((await errorOf(b)).code).toBe('AI_RESPONSE_IN_PROGRESS');
    release();
    await okOf(await a);
    expect(n).toBe(1);
  });

  it('a Coach clientMessageId reused as clientRequestId is 409 CLIENT_MESSAGE_ID_CONFLICT', async () => {
    const u = newUser();
    const crid = crypto.randomUUID();
    await db.execute(sql`
      INSERT INTO ai_responses (id, user_id, client_message_id, kind, status, content, created_at, updated_at)
      VALUES (${crypto.randomUUID()}, ${u}, ${crid}, 'coach', 'complete', 'hola', ${NOW}, ${NOW})`);
    const { provider, calls } = fakeProvider(modelOutput);
    h.deps = await dbDeps(provider);
    const res = await post(request({ clientRequestId: crid }));
    expect(res.status).toBe(409);
    expect((await errorOf(res)).code).toBe('CLIENT_MESSAGE_ID_CONFLICT');
    expect(calls).toHaveLength(0);
  });
});
