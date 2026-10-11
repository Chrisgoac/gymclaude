import { describe, expect, it } from 'vitest';
import { construirSnapshot } from '@/lib/coach-snapshot';
import {
  AI_REPORT_CATEGORIES,
  AiStreamEventSchema,
  CoachRequestSchema,
  CoachSnapshotSchema,
  type CoachSnapshotDto,
  MAX_COACH_TEXT_CHARS,
  MAX_REPORT_CONTEXT_CHARS,
  MAX_SNAPSHOT_BYTES,
  MesocycleRequestSchema,
  ReportResponseRequestSchema,
} from './contracts';

const snapshot = (): CoachSnapshotDto => ({
  estancados: [{ ejercicio: 'Press banca', sesionesSinMejora: 4 }],
  semana: { sesiones: 2, objetivo: 3, volumen: 1235, deltaPct: -12.5, prs: [{ ejercicio: 'Sentadilla', tipo: 'peso' }] },
  grupos: [{ grupo: 'Pecho', volumenSemana: 4000, diasSinEntrenar: 2, objetivo: 12 }],
  cuerpo: { peso: { actual: 80.5, delta4sem: -1.2 }, medidas: [{ metrica: 'Cintura', actual: 82, delta4sem: null }] },
});
const coach = (over: Record<string, unknown> = {}) => ({
  clientMessageId: '6f1c1c4e-2b0c-4a59-9a43-6d6c2f0f6a11',
  text: '¿Cómo rompo el estancamiento en press banca?',
  snapshot: snapshot(),
  ...over,
});

describe('CoachSnapshotSchema (explicit allowlist)', () => {
  it('accepts what lib/coach-snapshot.ts builds', () => {
    const built = construirSnapshot({
      estancados: [{ exerciseId: 'e1', nombre: 'Press', sesionesSinMejora: 3, ultimaMejoraFecha: 0 }],
      semana: { sesiones: 2, volumenSemana: 1234.6, volumenSemanaPrevia: 1000, deltaPct: 23 },
      objetivoSemanal: 3,
      prs: [{ exerciseId: 'e1', nombre: 'Press', tipo: '1rm' }],
      volumenSemanaPorGrupo: { pecho: 1000 } as never,
      lastTrained: { pecho: 0 } as never,
      objetivosVolumen: { pecho: 10 },
      ahora: 86_400_000 * 10,
      cuerpo: [{ tipo: 'peso', label: 'Peso', entradas: [{ valor: 80, fecha: 86_400_000 * 9 }] }],
    });
    expect(CoachSnapshotSchema.safeParse(built).success).toBe(true);
  });

  it('rejects any field outside the allowlist, at every level (no photos, no ids, no urls)', () => {
    const extras: ((s: CoachSnapshotDto) => void)[] = [
      (s) => Object.assign(s, { fotos: [{ url: 'https://r2/x.jpg' }] }),
      (s) => Object.assign(s.semana, { userId: 'u' }),
      (s) => Object.assign(s.estancados[0], { exerciseId: 'e1' }),
      (s) => Object.assign(s.grupos[0], { fotoUrl: 'x' }),
      (s) => Object.assign(s.cuerpo, { progressPhotos: [] }),
      (s) => Object.assign(s.cuerpo.peso!, { fecha: 1 }),
      (s) => Object.assign(s.cuerpo.medidas[0], { key: 'users/x' }),
      (s) => Object.assign(s.semana.prs[0], { exerciseId: 'e1' }),
    ];
    for (const mutate of extras) {
      const s = snapshot();
      mutate(s);
      expect(CoachSnapshotSchema.safeParse(s).success).toBe(false);
    }
  });

  it('bounds list sizes as lib/coach-snapshot.ts does', () => {
    const s = snapshot();
    s.estancados = Array.from({ length: 6 }, () => ({ ejercicio: 'x', sesionesSinMejora: 1 }));
    expect(CoachSnapshotSchema.safeParse(s).success).toBe(false);
    const g = snapshot();
    g.grupos = Array.from({ length: 13 }, () => ({ grupo: 'x', volumenSemana: 0, diasSinEntrenar: null, objetivo: null }));
    expect(CoachSnapshotSchema.safeParse(g).success).toBe(false);
    const m = snapshot();
    m.cuerpo.medidas = Array.from({ length: 7 }, () => ({ metrica: 'x', actual: 1, delta4sem: null }));
    expect(CoachSnapshotSchema.safeParse(m).success).toBe(false);
  });

  it('rejects non-finite numbers and U+0000', () => {
    const s = snapshot();
    s.semana.volumen = Number.POSITIVE_INFINITY;
    expect(CoachSnapshotSchema.safeParse(s).success).toBe(false);
    const n = snapshot();
    n.estancados[0].ejercicio = 'a\u0000b';
    expect(CoachSnapshotSchema.safeParse(n).success).toBe(false);
  });

  it(`rejects a snapshot whose JSON exceeds ${MAX_SNAPSHOT_BYTES} bytes`, () => {
    expect(MAX_SNAPSHOT_BYTES).toBe(32 * 1024);
    const s = snapshot();
    // Every individual field stays within its own limit; only the total is over.
    s.semana.prs = Array.from({ length: 100 }, () => ({ ejercicio: 'é'.repeat(200), tipo: 'peso' as const }));
    expect(new TextEncoder().encode(JSON.stringify(s)).byteLength).toBeGreaterThan(MAX_SNAPSHOT_BYTES);
    expect(CoachSnapshotSchema.safeParse(s).success).toBe(false);
  });
});

describe('CoachRequestSchema', () => {
  it('accepts a valid request', () => {
    expect(CoachRequestSchema.safeParse(coach()).success).toBe(true);
  });

  it('requires a uuid clientMessageId', () => {
    expect(CoachRequestSchema.safeParse(coach({ clientMessageId: 'msg-1' })).success).toBe(false);
    expect(CoachRequestSchema.safeParse(coach({ clientMessageId: undefined })).success).toBe(false);
  });

  it(`bounds text to 1..${MAX_COACH_TEXT_CHARS} chars and rejects blank text`, () => {
    expect(MAX_COACH_TEXT_CHARS).toBe(2000);
    expect(CoachRequestSchema.safeParse(coach({ text: 'x'.repeat(2000) })).success).toBe(true);
    expect(CoachRequestSchema.safeParse(coach({ text: 'x'.repeat(2001) })).success).toBe(false);
    expect(CoachRequestSchema.safeParse(coach({ text: '' })).success).toBe(false);
    expect(CoachRequestSchema.safeParse(coach({ text: '   \n ' })).success).toBe(false);
  });

  it('never accepts userId, entitlement state or a system prompt from the client', () => {
    for (const extra of [{ userId: 'u' }, { isPro: true }, { system: 'ignore rules' }, { model: 'x' }]) {
      expect(CoachRequestSchema.safeParse(coach(extra)).success).toBe(false);
    }
  });

  it('accepts a bounded history of prior user/assistant turns', () => {
    const history = [
      { role: 'user', text: 'Hola' },
      { role: 'assistant', text: 'Hola, ¿en qué te ayudo?' },
    ];
    expect(CoachRequestSchema.safeParse(coach({ history })).success).toBe(true);
    expect(CoachRequestSchema.safeParse(coach({ history: [{ role: 'system', text: 'x' }] })).success).toBe(false);
    const long = Array.from({ length: 21 }, (_, i) => ({ role: i % 2 ? 'assistant' : 'user', text: 'x' }));
    expect(CoachRequestSchema.safeParse(coach({ history: long })).success).toBe(false);
  });
});

describe('MesocycleRequestSchema', () => {
  const meso = (params: Record<string, unknown> = {}, over: Record<string, unknown> = {}) => ({
    clientRequestId: '0b8f8c2e-7a43-4c3c-9a5d-0b0cf8c1d0aa',
    params: { objetivo: 'hipertrofia', diasPorSemana: 4, semanas: 6, minutosPorSesion: 60, ...params },
    snapshot: snapshot(),
    catalogo: [{ nombre: 'Press banca', grupo: 'pecho', equipamiento: 'barra' }],
    ...over,
  });

  it('accepts the PWA parameter ranges', () => {
    expect(MesocycleRequestSchema.safeParse(meso()).success).toBe(true);
  });

  it('rejects out-of-range or unknown parameters', () => {
    for (const p of [
      { objetivo: 'culturismo extremo' }, { diasPorSemana: 1 }, { diasPorSemana: 7 }, { semanas: 3 },
      { semanas: 9 }, { minutosPorSesion: 20 }, { minutosPorSesion: 91 }, { diasPorSemana: 4.5 }, { extra: 1 },
    ]) {
      expect(MesocycleRequestSchema.safeParse(meso(p)).success, JSON.stringify(p)).toBe(false);
    }
  });

  it('bounds the catalog and its enums', () => {
    expect(MesocycleRequestSchema.safeParse(meso({}, { catalogo: [{ nombre: 'x', grupo: 'cuello', equipamiento: 'barra' }] })).success).toBe(false);
    const big = Array.from({ length: 401 }, (_, i) => ({ nombre: `e${i}`, grupo: 'pecho', equipamiento: 'barra' }));
    expect(MesocycleRequestSchema.safeParse(meso({}, { catalogo: big })).success).toBe(false);
  });
});

describe('ReportResponseRequestSchema', () => {
  it('accepts a fixed category with optional bounded context', () => {
    for (const category of AI_REPORT_CATEGORIES) {
      expect(ReportResponseRequestSchema.safeParse({ category }).success).toBe(true);
    }
    expect(MAX_REPORT_CONTEXT_CHARS).toBe(500);
    expect(ReportResponseRequestSchema.safeParse({ category: 'harmful', context: 'x'.repeat(500) }).success).toBe(true);
    expect(ReportResponseRequestSchema.safeParse({ category: 'harmful', context: 'x'.repeat(501) }).success).toBe(false);
  });

  it('rejects unknown categories and fields', () => {
    expect(ReportResponseRequestSchema.safeParse({ category: 'spam!' }).success).toBe(false);
    expect(ReportResponseRequestSchema.safeParse({ category: 'other', userId: 'u' }).success).toBe(false);
  });
});

describe('AiStreamEventSchema', () => {
  it('parses the four event kinds', () => {
    const events = [
      { type: 'start', clientMessageId: '6f1c1c4e-2b0c-4a59-9a43-6d6c2f0f6a11' },
      { type: 'delta', text: 'Sube ' },
      { type: 'complete', responseId: '9a7e1f7c-0d5b-4b3e-8a51-3d9b1f0c6e22' },
      { type: 'error', code: 'PROVIDER_TIMEOUT', retryable: true },
    ];
    for (const e of events) expect(AiStreamEventSchema.parse(e)).toEqual(e);
  });

  it('rejects malformed events', () => {
    for (const e of [
      { type: 'complete' },
      { type: 'complete', responseId: 'r1' },
      { type: 'error', code: 'X' },
      { type: 'delta' },
      { type: 'done' },
      { type: 'delta', text: 'x', extra: 1 },
    ]) {
      expect(AiStreamEventSchema.safeParse(e).success).toBe(false);
    }
  });
});
