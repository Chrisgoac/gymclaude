// @vitest-environment node
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
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
import { IntelligentReportResponseSchema, type CoachSnapshotDto } from '@/lib/api/v1/ai/contracts';
import type { StoredAiReport } from '@/lib/api/v1/ai/store';
import { AiOutputError, type AiObjectInput, type AiObjectProvider } from '@/lib/coach-model';
import { MEDICAL_DISCLAIMER_INSTRUCTION } from '@/lib/coach-prompt';
import { REPORT_KIND, REPORT_SCHEMA, reportFingerprint, type ReportDeps, type ReportStore } from '@/lib/intelligent-report';

const h = vi.hoisted(() => ({
  deps: undefined as unknown as ReportDeps,
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
vi.mock('@/lib/api/v1/ai/runtime', () => ({ getReportDeps: () => h.deps }));

import { POST } from './route';

const USER = 'user_1';
const NOW = 1_767_225_600_000;

const snapshot = (over: Partial<CoachSnapshotDto> = {}): CoachSnapshotDto => ({
  estancados: [{ ejercicio: 'Press banca', sesionesSinMejora: 4 }],
  semana: { sesiones: 3, objetivo: 3, volumen: 9000, deltaPct: 5, prs: [{ ejercicio: 'Sentadilla', tipo: 'peso' }] },
  grupos: [{ grupo: 'Pecho', volumenSemana: 4000, diasSinEntrenar: 2, objetivo: 12 }],
  cuerpo: { peso: { actual: 80, delta4sem: -1 }, medidas: [] },
  ...over,
});
const report = () => ({
  resumen: 'Buena semana: cumples el objetivo de sesiones.',
  puntosFuertes: ['Constancia'],
  aMejorar: ['Press banca estancado'],
  recomendaciones: ['Cambia el rango de reps del press banca'],
});

const post = (payload: unknown, init: RequestInit = {}) =>
  POST(new Request('http://x/api/v1/ai/reports', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: typeof payload === 'string' ? payload : JSON.stringify(payload),
    ...init,
  }));
const errorOf = async (res: Response) => ApiErrorBodySchema.parse(await res.json()).error;
const okOf = async (res: Response) => {
  const raw = await res.text();
  expect(res.status, raw).toBe(200);
  return IntelligentReportResponseSchema.parse(JSON.parse(raw));
};

function memoryStore() {
  const rows: (StoredAiReport & { userId: string; kind: string; fingerprint: string })[] = [];
  return {
    rows,
    find: vi.fn(async (userId: string, kind: string, fingerprint: string) =>
      rows.find((r) => r.userId === userId && r.kind === kind && r.fingerprint === fingerprint) ?? null),
    store: vi.fn(async (userId: string, kind: string, fingerprint: string, input: { id: string; payload: unknown; model: string; createdAt: number }) => {
      for (let i = rows.length - 1; i >= 0; i--) {
        if (rows[i].userId === userId && rows[i].kind === kind && rows[i].fingerprint !== fingerprint) rows.splice(i, 1);
      }
      const row = { ...input, userId, kind, fingerprint };
      rows.push(row);
      return row;
    }),
  } satisfies ReportStore & { rows: unknown[] };
}
function fakeProvider(answer: () => unknown) {
  const calls: AiObjectInput[] = [];
  const provider: AiObjectProvider = { async generate(input) { calls.push(input); return answer(); } };
  return { provider, calls };
}

let store: ReturnType<typeof memoryStore>;
let clock = NOW;
let ids = 0;
let warn: MockInstance<typeof console.warn>;
let info: MockInstance<typeof console.info>;
let error: MockInstance<typeof console.error>;
const setDeps = (provider: AiObjectProvider, over: Partial<ReportDeps> = {}) => {
  h.deps = { store, provider, now: () => clock, newId: () => `report-${++ids}`, ...over };
};
const logged = () => [...info.mock.calls, ...warn.mock.calls, ...error.mock.calls].flat().map(String).join('\n');

beforeEach(() => {
  setAuthSourceForTests(async () => ({ userId: USER }));
  h.isPro.mockResolvedValue(true);
  h.configured.mockReturnValue(true);
  store = memoryStore();
  clock = NOW;
  ids = 0;
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

describe('POST /api/v1/ai/reports — authorization', () => {
  it('401 / 403 / 503 entitlement before reading the cache or calling the model', async () => {
    const { provider, calls } = fakeProvider(report);
    setDeps(provider);
    setAuthSourceForTests(async () => ({ userId: null }));
    expect((await post({ snapshot: snapshot() })).status).toBe(401);
    setAuthSourceForTests(async () => ({ userId: USER }));

    h.isPro.mockResolvedValueOnce(false);
    const denied = await post({ snapshot: snapshot() });
    expect(denied.status).toBe(403);
    expect((await errorOf(denied)).code).toBe('PRO_REQUIRED');

    h.isPro.mockRejectedValueOnce(new Error('down'));
    expect((await errorOf(await post({ snapshot: snapshot() }))).code).toBe('ENTITLEMENT_UNAVAILABLE');
    expect(store.find).not.toHaveBeenCalled();
    expect(h.rate).not.toHaveBeenCalled();
    expect(calls).toHaveLength(0);
  });

  it('on a cache miss: 503 AI_UNAVAILABLE and 429 (minute or daily budget) before calling the model', async () => {
    const { provider, calls } = fakeProvider(report);
    setDeps(provider);
    h.configured.mockReturnValueOnce(false);
    expect((await errorOf(await post({ snapshot: snapshot() }))).code).toBe('AI_UNAVAILABLE');
    expect(h.rate).not.toHaveBeenCalled();

    h.rate.mockRejectedValueOnce(new RateLimitedError(5));
    const limited = await post({ snapshot: snapshot() });
    expect(limited.status).toBe(429);
    expect(limited.headers.get('retry-after')).toBe('5');

    // Por minuto pasa, el presupuesto diario está agotado.
    h.rate.mockResolvedValueOnce(undefined).mockRejectedValueOnce(new RateLimitedError(3600));
    const daily = await post({ snapshot: snapshot() });
    expect(daily.status).toBe(429);
    expect((await errorOf(daily)).code).toBe('RATE_LIMITED');
    expect(daily.headers.get('retry-after')).toBe('3600');
    expect(h.rate.mock.calls.slice(-2)).toEqual([['ai', USER], ['aiDaily', USER]]);
    expect(calls).toHaveLength(0);
    expect(store.rows).toHaveLength(0);
  });

  it('a cache hit is served without DeepSeek configured and without spending AI quota', async () => {
    const { provider, calls } = fakeProvider(report);
    setDeps(provider);
    await okOf(await post({ snapshot: snapshot() }));
    expect(h.rate).toHaveBeenCalledTimes(2);
    h.rate.mockClear();

    h.configured.mockReturnValue(false);
    h.rate.mockRejectedValue(new RateLimitedError(60));
    const hit = await okOf(await post({ snapshot: snapshot() }));
    expect(hit).toMatchObject({ reportId: 'report-1', cached: true });
    expect(h.rate).not.toHaveBeenCalled();
    expect(calls).toHaveLength(1);
    h.rate.mockReset();
    h.rate.mockImplementation(async () => {});
  });

  it('400 for anything beyond the snapshot allowlist (photos, ids, extra fields)', async () => {
    const { provider, calls } = fakeProvider(report);
    setDeps(provider);
    const withPhoto = snapshot();
    Object.assign(withPhoto.cuerpo, { fotos: [{ url: 'https://r2/x.jpg' }] });
    for (const bad of [{}, { snapshot: withPhoto }, { snapshot: snapshot(), userId: 'x' }, { snapshot: snapshot(), periodo: '3m' }]) {
      const res = await post(bad);
      expect(res.status).toBe(400);
      expect((await errorOf(res)).code).toBe('INVALID_REQUEST');
    }
    expect(calls).toHaveLength(0);
  });
});

describe('POST /api/v1/ai/reports — fingerprint cache', () => {
  it('generates, stores and returns the report with the disclaimer; the prompt has the medical rule', async () => {
    const { provider, calls } = fakeProvider(report);
    setDeps(provider);
    const out = await okOf(await post({ snapshot: snapshot() }));
    expect(out).toEqual({ reportId: 'report-1', generatedAt: NOW, cached: false, report: report(), disclaimer: true });
    expect(calls[0].schema).toBe(REPORT_SCHEMA);
    expect(calls[0].system).toContain(MEDICAL_DISCLAIMER_INSTRUCTION);
    expect(calls[0].prompt).toContain('Press banca');
    expect(store.rows).toHaveLength(1);
    expect(store.rows[0]).toMatchObject({ userId: USER, kind: REPORT_KIND, fingerprint: reportFingerprint(snapshot()) });
  });

  it('the same source returns the cached report (original date) without calling the model', async () => {
    const { provider, calls } = fakeProvider(report);
    setDeps(provider);
    await okOf(await post({ snapshot: snapshot() }));
    clock = NOW + 3_600_000;
    const again = await okOf(await post({ snapshot: snapshot() }));
    expect(again).toMatchObject({ reportId: 'report-1', generatedAt: NOW, cached: true });
    expect(calls).toHaveLength(1);
  });

  it('the fingerprint ignores key order but changes with any value: a changed source regenerates and invalidates', async () => {
    const reordered = JSON.parse(JSON.stringify({ cuerpo: snapshot().cuerpo, grupos: snapshot().grupos, semana: snapshot().semana, estancados: snapshot().estancados }));
    expect(reportFingerprint(reordered)).toBe(reportFingerprint(snapshot()));
    const changed = snapshot({ semana: { ...snapshot().semana, sesiones: 4 } });
    expect(reportFingerprint(changed)).not.toBe(reportFingerprint(snapshot()));

    const { provider, calls } = fakeProvider(report);
    setDeps(provider);
    await okOf(await post({ snapshot: snapshot() }));
    clock = NOW + 1000;
    const fresh = await okOf(await post({ snapshot: changed }));
    expect(fresh).toMatchObject({ reportId: 'report-2', generatedAt: NOW + 1000, cached: false });
    expect(calls).toHaveLength(2);
    expect(store.rows.map((r) => r.id)).toEqual(['report-2']);
    // Back to the old source: the old report was dropped, so it is generated again.
    await okOf(await post({ snapshot: snapshot() }));
    expect(calls).toHaveLength(3);
  });

  it.each([
    ['not an object', () => ['x']],
    ['missing resumen', () => ({ ...report(), resumen: undefined })],
    ['no recommendation', () => ({ ...report(), recomendaciones: ['  '] })],
    ['non-string item', () => ({ ...report(), puntosFuertes: [42] })],
  ])('malformed output (%s) → 502 AI_BAD_OUTPUT retryable, nothing cached', async (_n, answer) => {
    setDeps(fakeProvider(answer).provider);
    const res = await post({ snapshot: snapshot() });
    expect(res.status).toBe(502);
    expect(await errorOf(res)).toMatchObject({ code: 'AI_BAD_OUTPUT', retryable: true });
    expect(store.rows).toHaveLength(0);
  });

  it('clips long texts and caps lists to the contract', async () => {
    setDeps(fakeProvider(() => ({ ...report(), resumen: 'a'.repeat(5000), aMejorar: Array.from({ length: 9 }, (_, i) => `Punto ${i}`) })).provider);
    const out = await okOf(await post({ snapshot: snapshot() }));
    expect(out.report.resumen).toHaveLength(1500);
    expect(out.report.aMejorar).toHaveLength(5);
  });

  it('AiOutputError → AI_BAD_OUTPUT; provider error → AI_PROVIDER_ERROR; slow provider → AI_TIMEOUT; logs carry no data', async () => {
    setDeps({ generate: async () => { throw new AiOutputError(); } });
    expect((await errorOf(await post({ snapshot: snapshot() }))).code).toBe('AI_BAD_OUTPUT');
    setDeps({ generate: async () => { throw new Error('upstream failed for Press banca'); } });
    expect((await errorOf(await post({ snapshot: snapshot() }))).code).toBe('AI_PROVIDER_ERROR');
    setDeps({ generate: () => new Promise(() => {}) }, { timeoutMs: 20 });
    const slow = await post({ snapshot: snapshot() });
    expect(slow.status).toBe(504);
    expect((await errorOf(slow)).code).toBe('AI_TIMEOUT');
    setDeps(fakeProvider(report).provider);
    await okOf(await post({ snapshot: snapshot() }));
    for (const secret of ['Press banca', 'Constancia', 'Sentadilla']) expect(logged()).not.toContain(secret);
    expect(store.rows).toHaveLength(1);
  });
});

// ── Against a disposable Postgres (SYNC_TEST_DATABASE_URL): real ai_reports ──

const url = process.env.SYNC_TEST_DATABASE_URL;
describe.skipIf(!url)('POST /api/v1/ai/reports (integration)', () => {
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
      await db.execute(sql`DELETE FROM ai_reports WHERE user_id IN (${list})`);
    }
    await pool.end();
  });
  const newUser = () => {
    const u = `test-report-${crypto.randomUUID()}`;
    users.push(u);
    setAuthSourceForTests(async () => ({ userId: u }));
    return u;
  };
  const dbDeps = async (provider: AiObjectProvider): Promise<ReportDeps> => {
    const s = await import('@/lib/api/v1/ai/store');
    return {
      provider,
      now: () => clock,
      store: {
        find: (u, k, f) => s.findAiReport(db, u, k, f),
        store: (u, k, f, input) => s.storeAiReport(db, u, k, f, input),
      },
    };
  };
  const rowsOf = async (u: string) =>
    (await db.execute(sql`SELECT source_fingerprint, created_at FROM ai_reports WHERE user_id = ${u}`)).rows;

  it('caches per user + fingerprint; a new source replaces the old row; other users are unaffected', async () => {
    const other = newUser();
    const { provider, calls } = fakeProvider(report);
    h.deps = await dbDeps(provider);
    await okOf(await post({ snapshot: snapshot() }));
    const u = newUser();
    const first = await okOf(await post({ snapshot: snapshot() }));
    expect(first.cached).toBe(false);
    expect(calls).toHaveLength(2); // the other user's report is never served to this one
    const cached = await okOf(await post({ snapshot: snapshot() }));
    expect(cached).toEqual({ ...first, cached: true });
    expect(calls).toHaveLength(2);

    clock = NOW + 5000;
    const changed = snapshot({ estancados: [] });
    await okOf(await post({ snapshot: changed }));
    expect(await rowsOf(u)).toEqual([{ source_fingerprint: reportFingerprint(changed), created_at: String(NOW + 5000) }]);
    expect(await rowsOf(other)).toHaveLength(1);
  });

  it('a concurrent insert of the same fingerprint keeps the first row', async () => {
    const u = newUser();
    const s = await import('@/lib/api/v1/ai/store');
    const fp = reportFingerprint(snapshot());
    const a = await s.storeAiReport(db, u, REPORT_KIND, fp, { id: crypto.randomUUID(), payload: report(), model: 'm', createdAt: NOW });
    const b = await s.storeAiReport(db, u, REPORT_KIND, fp, { id: crypto.randomUUID(), payload: { other: true }, model: 'm', createdAt: NOW + 1 });
    expect(b).toEqual(a);
    expect(b.payload).toEqual(report());
  });
});
