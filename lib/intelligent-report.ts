import { createHash } from 'node:crypto';
import {
  IntelligentReportSchema,
  REPORT_LIMITS,
  type CoachSnapshotDto,
  type IntelligentReport,
  type IntelligentReportResponse,
} from '@/lib/api/v1/ai/contracts';
import type { StoredAiReport } from '@/lib/api/v1/ai/store';
import { ApiError } from '@/lib/api/v1/errors';
import { AiOutputError, MODELO_COACH, type AiObjectProvider } from '@/lib/coach-model';
import { MEDICAL_DISCLAIMER_INSTRUCTION } from '@/lib/coach-prompt';

// POST /api/v1/ai/reports: a Pro report over the user's allowlisted progress snapshot (the Coach's
// CoachSnapshotSchema: no photos, ids, URLs or keys). Reports are server-owned rows of ai_reports keyed
// by (user, kind, source fingerprint): an identical source returns the stored report without calling
// the model; a changed source generates a new one and drops the older ones. Never part of sync.

/** Bump when the prompt or report shape changes: it is part of the kind, so old reports stop matching. */
export const REPORT_KIND = 'progress-v1';
export const REPORT_TIMEOUT_MS = 60_000;
export const REPORT_MAX_OUTPUT_TOKENS = 1500;

export const REPORT_SCHEMA = {
  type: 'object',
  properties: {
    resumen: { type: 'string' },
    puntosFuertes: { type: 'array', items: { type: 'string' } },
    aMejorar: { type: 'array', items: { type: 'string' } },
    recomendaciones: { type: 'array', items: { type: 'string' } },
  },
  required: ['resumen', 'puntosFuertes', 'aMejorar', 'recomendaciones'],
  additionalProperties: false,
} as const;

export const reportBadOutput = () => new ApiError(502, 'AI_BAD_OUTPUT', 'The AI returned an invalid report; retry', true);
const providerError = () => new ApiError(502, 'AI_PROVIDER_ERROR', 'The AI provider failed; retry', true);
const timeout = () => new ApiError(504, 'AI_TIMEOUT', 'The AI took too long; retry', true);

export interface ReportStore {
  find(userId: string, kind: string, fingerprint: string): Promise<StoredAiReport | null>;
  store(
    userId: string,
    kind: string,
    fingerprint: string,
    input: { id: string; payload: unknown; model: string; createdAt: number },
  ): Promise<StoredAiReport>;
}

export interface ReportDeps {
  store: ReportStore;
  provider: AiObjectProvider;
  now?: () => number;
  newId?: () => string;
  timeoutMs?: number;
}

/** JSON with object keys sorted at every level: equal values always give equal text. */
export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, v]) => v !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
    return `{${entries.map(([k, v]) => `${JSON.stringify(k)}:${canonicalJson(v)}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

/** SHA-256 of the report kind + canonical snapshot. Any change of the source data changes it. */
export function reportFingerprint(snapshot: CoachSnapshotDto, kind = REPORT_KIND): string {
  return createHash('sha256').update(`${kind}\n${canonicalJson(snapshot)}`).digest('hex');
}

export function reportSystemPrompt(): string {
  return [
    'Eres un entrenador de fuerza e hipertrofia. Escribes un informe breve en español sobre el progreso del atleta a partir de sus datos, y respondes solo con el objeto JSON pedido.',
    `- "resumen": 2-4 frases (máx. ${REPORT_LIMITS.summaryChars} caracteres).`,
    `- "puntosFuertes", "aMejorar", "recomendaciones": hasta ${REPORT_LIMITS.items} frases cortas cada una (máx. ${REPORT_LIMITS.itemChars} caracteres); al menos una recomendación concreta de entrenamiento.`,
    '- Básate solo en los datos dados; si faltan datos, dilo y recomienda registrar entrenamientos.',
    MEDICAL_DISCLAIMER_INSTRUCTION,
  ].join('\n');
}

export function reportPrompt(snapshot: CoachSnapshotDto): string {
  return ['Datos del atleta (JSON):', JSON.stringify(snapshot)].join('\n');
}

const clean = (v: unknown, max: number) => {
  if (typeof v !== 'string') return v;
  const s = v.replace(/\u0000/g, '').trim();
  if (s.length <= max) return s;
  const cut = s.slice(0, max);
  const last = cut.charCodeAt(cut.length - 1);
  return last >= 0xd800 && last <= 0xdbff ? cut.slice(0, -1) : cut;
};
const cleanList = (v: unknown) =>
  Array.isArray(v)
    ? v
        .map((item) => clean(item, REPORT_LIMITS.itemChars))
        .filter((item) => item !== '')
        .slice(0, REPORT_LIMITS.items)
    : v;

/** Validates the model's raw object (texts trimmed/clipped, lists capped), or throws AI_BAD_OUTPUT. */
export function parseReportOutput(raw: unknown): IntelligentReport {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) throw reportBadOutput();
  const r = raw as Record<string, unknown>;
  const parsed = IntelligentReportSchema.safeParse({
    resumen: clean(r.resumen, REPORT_LIMITS.summaryChars),
    puntosFuertes: cleanList(r.puntosFuertes),
    aMejorar: cleanList(r.aMejorar),
    recomendaciones: cleanList(r.recomendaciones),
  });
  if (!parsed.success) throw reportBadOutput();
  return parsed.data;
}

const logOutcome = (outcome: string, level: 'info' | 'warn' = 'info', extra?: string) =>
  console[level](`[api/v1/ai/reports] outcome=${outcome}${extra ? ` ${extra}` : ''}`);

/**
 * The cached report for this exact snapshot, or a freshly generated (and stored) one. `beforeGenerate`
 * runs only on a cache miss, before the model call (availability + AI budget checks).
 */
export async function getIntelligentReport(
  deps: ReportDeps,
  userId: string,
  snapshot: CoachSnapshotDto,
  options: { beforeGenerate?: () => Promise<void> } = {},
): Promise<IntelligentReportResponse> {
  const now = deps.now ?? Date.now;
  const fingerprint = reportFingerprint(snapshot);
  const toResponse = (row: StoredAiReport, cached: boolean): IntelligentReportResponse => ({
    reportId: row.id,
    generatedAt: row.createdAt,
    cached,
    report: IntelligentReportSchema.parse(row.payload),
    disclaimer: true,
  });

  const existing = await deps.store.find(userId, REPORT_KIND, fingerprint);
  if (existing) {
    logOutcome('cache_hit');
    return toResponse(existing, true);
  }
  // Solo una generación real gasta cupo de IA o necesita el proveedor configurado: un acierto de
  // caché no consume nada (lo decide la ruta con este gancho).
  await options.beforeGenerate?.();

  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timedOut = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      controller.abort(new Error('timeout'));
      reject(timeout());
    }, deps.timeoutMs ?? REPORT_TIMEOUT_MS);
  });
  let raw: unknown;
  try {
    raw = await Promise.race([
      deps.provider.generate({
        system: reportSystemPrompt(),
        prompt: reportPrompt(snapshot),
        schema: REPORT_SCHEMA,
        maxOutputTokens: REPORT_MAX_OUTPUT_TOKENS,
        abortSignal: controller.signal,
      }),
      timedOut,
    ]);
  } catch (error) {
    if (error instanceof ApiError) {
      logOutcome(error.code.toLowerCase(), 'warn');
      throw error;
    }
    if (error instanceof AiOutputError) {
      logOutcome('ai_bad_output', 'warn');
      throw reportBadOutput();
    }
    logOutcome('provider_error', 'warn', `error=${error instanceof Error ? error.name : typeof error}`);
    throw providerError();
  } finally {
    clearTimeout(timer);
  }
  let report: IntelligentReport;
  try {
    report = parseReportOutput(raw);
  } catch (error) {
    logOutcome('ai_bad_output', 'warn');
    throw error;
  }
  const stored = await deps.store.store(userId, REPORT_KIND, fingerprint, {
    id: (deps.newId ?? (() => crypto.randomUUID()))(),
    payload: report,
    model: MODELO_COACH,
    createdAt: now(),
  });
  logOutcome('generated');
  // A concurrent request may have stored the same fingerprint first: its row is the one returned.
  return toResponse(stored, false);
}
