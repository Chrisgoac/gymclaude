import {
  MESO_DRAFT_LIMITS,
  MesocycleDraftSchema,
  type MesocycleDraft,
  type MesocycleRequest,
  type MesocycleResponse,
} from '@/lib/api/v1/ai/contracts';
import type { CoachClaimInput, CoachResponseClaim } from '@/lib/api/v1/ai/store';
import { ApiError } from '@/lib/api/v1/errors';
import { AiOutputError, type AiObjectProvider } from '@/lib/coach-model';
import { MEDICAL_DISCLAIMER_INSTRUCTION } from '@/lib/coach-prompt';
import { MESO_SCHEMA } from '@/lib/meso-prompt';

// POST /api/v1/ai/mesocycles: one structured model call per clientRequestId, validated into a DRAFT.
// Nothing of the user's data is written: the only row is the ai_responses claim (kind 'mesocycle'),
// whose content becomes the validated draft, so a retry of the same clientRequestId replays it
// without calling (and paying for) the model again.

/** The model call is aborted after this long (route maxDuration is 120 s). */
export const MESO_TIMEOUT_MS = 90_000;
/** An attempt's lease outlives its model timeout by this much (covers the final DB write). */
export const MESO_LEASE_MARGIN_MS = 15_000;
export const MESO_MAX_OUTPUT_TOKENS = 4000;

export const aiBadOutput = () =>
  new ApiError(502, 'AI_BAD_OUTPUT', 'The AI returned an invalid mesocycle; retry', true);
export const aiProviderError = () => new ApiError(502, 'AI_PROVIDER_ERROR', 'The AI provider failed; retry', true);
export const aiTimeout = () => new ApiError(504, 'AI_TIMEOUT', 'The AI took too long; retry', true);
export const aiResponseConflict = () =>
  new ApiError(409, 'AI_RESPONSE_CONFLICT', 'A newer attempt took over this request; retry', true);
/** The clientRequestId was already used with different parameters. */
export const clientRequestIdReused = () =>
  new ApiError(409, 'CLIENT_MESSAGE_ID_CONFLICT', 'This clientRequestId was used for another request', false);

export interface MesocycleStore {
  /** Throws 409 AI_RESPONSE_IN_PROGRESS while another attempt holds a live lease. */
  claim(userId: string, clientRequestId: string, input: CoachClaimInput): Promise<CoachResponseClaim>;
  complete(userId: string, responseId: string, attemptId: string, content: string, now: number): Promise<boolean>;
  release(userId: string, responseId: string, attemptId: string, now: number): Promise<void>;
}

export interface MesocycleDeps {
  store: MesocycleStore;
  provider: AiObjectProvider;
  now?: () => number;
  newId?: () => string;
  timeoutMs?: number;
}

/** Name normalization used to match the model's exercises to the catalog (same as lib/save-mesocycle.ts). */
export function normalizeExerciseName(s: string): string {
  return s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim().replace(/\s+/g, ' ');
}

/** System prompt of the v1 generator: catalog-only exercises, sane targets, no medical advice. */
export function mesocycleSystemPrompt(): string {
  const l = MESO_DRAFT_LIMITS;
  return [
    'Eres un entrenador de fuerza experto. Diseñas mesociclos de entrenamiento y respondes solo con el objeto JSON pedido.',
    'Reglas estrictas:',
    '- Usa ÚNICAMENTE ejercicios del catálogo proporcionado, escribiendo su nombre EXACTO. Nunca inventes ejercicios ni uses nuevo=true.',
    `- Cada día tiene entre 1 y ${l.exercisesPerDay} ejercicios. seriesObjetivo ${l.sets.min}-${l.sets.max}, repsObjetivo ${l.reps.min}-${l.reps.max}, descansoSegundos ${l.restSeconds.min}-${l.restSeconds.max}, todos enteros.`,
    '- "progresion" tiene exactamente una entrada por semana, semana = 1, 2, 3… en orden.',
    '- "semanas" y "diasPorSemana" son exactamente los pedidos, y "dias" tiene exactamente diasPorSemana elementos con orden 1, 2, 3…',
    MEDICAL_DISCLAIMER_INSTRUCTION,
  ].join('\n');
}

/** User prompt: parameters + allowlisted snapshot + catalog names. Pure. */
export function mesocyclePrompt(request: MesocycleRequest): string {
  const { params, snapshot, catalogo } = request;
  const lista = catalogo.map((e) => `- ${e.nombre} (${e.grupo}, ${e.equipamiento})`).join('\n');
  return [
    `Diseña un MESOCICLO. Objetivo: ${params.objetivo}. Días por semana: ${params.diasPorSemana}. Semanas: ${params.semanas}. Tiempo por sesión: ~${params.minutosPorSesion} min.`,
    '- Crea un split coherente; incluye una semana de descarga si procede (descarga=true, ajuste con menos volumen).',
    '- Ajusta el volumen por grupo según los datos del atleta (más volumen donde flojea, atención a los estancados).',
    '- Los targets (series/reps/descanso) son los de la semana 1; la progresión semanal va en "progresion[].ajuste" como texto breve.',
    '',
    'Datos del atleta (JSON):',
    JSON.stringify(snapshot),
    '',
    'Catálogo de ejercicios disponibles (usa solo estos nombres):',
    lista || '(vacío)',
  ].join('\n');
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const text = (v: unknown) => (typeof v === 'string' ? v.replace(/\u0000/g, '').trim() : '');
/** Cuts at `max` UTF-16 units without splitting a surrogate pair. */
const clip = (s: string, max: number) => {
  if (s.length <= max) return s;
  const cut = s.slice(0, max);
  const last = cut.charCodeAt(cut.length - 1);
  return last >= 0xd800 && last <= 0xdbff ? cut.slice(0, -1) : cut;
};

/**
 * Turns the model's raw object into a validated draft, or throws AI_BAD_OUTPUT. Exercises are matched to
 * the request catalog by normalized name and replaced by the catalog's exact name; unknown ones are
 * dropped and reported in `omitidos`. Week/day counts must be the requested ones; targets must be
 * integers in range. Free texts are trimmed and clipped to their limits.
 */
export function parseMesocycleOutput(raw: unknown, request: MesocycleRequest): { draft: MesocycleDraft; omitidos: string[] } {
  if (!isObject(raw) || !Array.isArray(raw.dias) || !Array.isArray(raw.progresion)) throw aiBadOutput();
  const l = MESO_DRAFT_LIMITS;
  const catalog = new Map<string, string>();
  for (const e of request.catalogo) {
    const key = normalizeExerciseName(e.nombre);
    if (!catalog.has(key)) catalog.set(key, e.nombre);
  }
  const omitidos: string[] = [];
  const days = raw.dias
    .map((d, index) => ({ d, index }))
    .filter(({ d }) => isObject(d))
    // Training order: the model's `orden`, then its list position.
    .sort((a, b) => num((a.d as Record<string, unknown>).orden, a.index) - num((b.d as Record<string, unknown>).orden, b.index) || a.index - b.index)
    .map(({ d }, i) => {
      const day = d as Record<string, unknown>;
      const ejercicios = (Array.isArray(day.ejercicios) ? day.ejercicios : []).flatMap((e) => {
        if (!isObject(e)) throw aiBadOutput();
        const name = text(e.nombre);
        const known = catalog.get(normalizeExerciseName(name));
        if (!known) {
          if (name && omitidos.length < l.omittedNames && !omitidos.includes(clip(name, 200))) omitidos.push(clip(name, 200));
          return [];
        }
        return [{ nombre: known, seriesObjetivo: e.seriesObjetivo, repsObjetivo: e.repsObjetivo, descansoSegundos: e.descansoSegundos }];
      });
      return { nombre: clip(text(day.nombre) || `Día ${i + 1}`, l.dayNameChars), ejercicios };
    });
  const progresion = [...raw.progresion]
    .filter(isObject)
    .sort((a, b) => num(a.semana, 0) - num(b.semana, 0))
    .map((p) => ({ semana: p.semana, descarga: p.descarga, ajuste: clip(text(p.ajuste), l.adjustmentChars) }));
  const notas = clip(text(raw.notas), l.notesChars);
  const candidate = {
    nombre: clip(text(raw.nombre), l.nameChars),
    objetivo: clip(text(raw.objetivo) || request.params.objetivo, l.goalChars),
    semanas: raw.semanas,
    diasPorSemana: raw.diasPorSemana,
    notas: notas || null,
    progresion,
    dias: days,
  };
  const parsed = MesocycleDraftSchema.safeParse(candidate);
  if (!parsed.success) throw aiBadOutput();
  const draft = parsed.data;
  if (draft.semanas !== request.params.semanas || draft.diasPorSemana !== request.params.diasPorSemana) throw aiBadOutput();
  return { draft, omitidos };
}

function num(v: unknown, fallback: number): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback;
}

/** What is stored in ai_responses.content for a complete mesocycle request. */
interface StoredMesocycle {
  paramsKey: string;
  draft: MesocycleDraft;
  omitidos: string[];
}

const paramsKeyOf = (r: MesocycleRequest) =>
  `${r.params.objetivo}|${r.params.diasPorSemana}|${r.params.semanas}|${r.params.minutosPorSesion}`;

// Ids and codes only: never prompt, snapshot, catalog or model output.
const logOutcome = (responseId: string, outcome: string, level: 'info' | 'warn' = 'warn', extra?: string) =>
  console[level](`[api/v1/ai/mesocycles] responseId=${responseId} outcome=${outcome}${extra ? ` ${extra}` : ''}`);

/**
 * Generates (or replays) the draft for one request. A complete row of the same clientRequestId is
 * returned without calling the model; reusing a clientRequestId with other parameters is 409.
 */
export async function generateMesocycleDraft(
  deps: MesocycleDeps,
  userId: string,
  request: MesocycleRequest,
  clientSignal?: AbortSignal,
): Promise<MesocycleResponse> {
  const now = deps.now ?? Date.now;
  const newId = deps.newId ?? (() => crypto.randomUUID());
  const timeoutMs = deps.timeoutMs ?? MESO_TIMEOUT_MS;
  const claimedAt = now();
  const claim = await deps.store.claim(userId, request.clientRequestId, {
    newId: newId(),
    attemptId: newId(),
    now: claimedAt,
    leaseExpiresAt: claimedAt + timeoutMs + MESO_LEASE_MARGIN_MS,
  });
  const respond = (responseId: string, stored: StoredMesocycle): MesocycleResponse => ({
    clientRequestId: request.clientRequestId,
    responseId,
    draft: stored.draft,
    omitidos: stored.omitidos,
    disclaimer: true,
  });

  if (claim.status === 'complete') {
    const stored = JSON.parse(claim.content) as StoredMesocycle;
    if (stored.paramsKey !== paramsKeyOf(request)) throw clientRequestIdReused();
    logOutcome(claim.responseId, 'replay', 'info');
    return respond(claim.responseId, { ...stored, draft: MesocycleDraftSchema.parse(stored.draft) });
  }

  const { responseId, attemptId } = claim;
  const controller = new AbortController();
  const onClientAbort = () => controller.abort(new Error('client disconnected'));
  clientSignal?.addEventListener('abort', onClientAbort, { once: true });
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timedOut = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      controller.abort(new Error('timeout'));
      reject(aiTimeout());
    }, timeoutMs);
  });
  try {
    let raw: unknown;
    try {
      // Raced so a provider that ignores its AbortSignal cannot hang the request.
      raw = await Promise.race([
        deps.provider.generate({
          system: mesocycleSystemPrompt(),
          prompt: mesocyclePrompt(request),
          schema: MESO_SCHEMA,
          maxOutputTokens: MESO_MAX_OUTPUT_TOKENS,
          abortSignal: controller.signal,
        }),
        timedOut,
      ]);
    } catch (error) {
      if (error instanceof ApiError) throw error;
      if (error instanceof AiOutputError) throw aiBadOutput();
      logOutcome(responseId, 'provider_error', 'warn', `error=${error instanceof Error ? error.name : typeof error}`);
      throw aiProviderError();
    }
    const { draft, omitidos } = parseMesocycleOutput(raw, request);
    const stored: StoredMesocycle = { paramsKey: paramsKeyOf(request), draft, omitidos };
    const won = await deps.store.complete(userId, responseId, attemptId, JSON.stringify(stored), now());
    if (!won) {
      logOutcome(responseId, 'conflict');
      throw aiResponseConflict();
    }
    logOutcome(responseId, 'complete', 'info');
    return respond(responseId, stored);
  } catch (error) {
    if (!(error instanceof ApiError && error.code === 'AI_RESPONSE_CONFLICT')) {
      if (error instanceof ApiError) logOutcome(responseId, error.code.toLowerCase());
      await deps.store.release(userId, responseId, attemptId, now()).catch(() => {});
    }
    throw error;
  } finally {
    clearTimeout(timer);
    clientSignal?.removeEventListener('abort', onClientAbort);
  }
}
