import { z } from 'zod';
import { EQUIPMENTS, MUSCLE_GROUPS, type Equipment, type MuscleGroup } from '@/lib/db/types';
import { Int32Schema, TextSchema } from '@/lib/api/v1/contracts/common';

// Wire contract of /api/v1/ai/*. Every object is strict: the client never supplies userId,
// entitlement state, model, or prompt text beyond the bounded fields below.

/** Max user message length for the Coach (chars). */
export const MAX_COACH_TEXT_CHARS = 2000;
/** Max serialized (UTF-8 JSON) size of a Coach snapshot. */
export const MAX_SNAPSHOT_BYTES = 32 * 1024;
/** Max UTF-8 bytes of a Coach request's text + serialized snapshot together. */
export const MAX_COACH_REQUEST_BYTES = 36 * 1024;
/** Max length of one streamed delta (chars). */
export const MAX_STREAM_DELTA_CHARS = 8000;
/** Max optional free-text context on a response report (chars). */
export const MAX_REPORT_CONTEXT_CHARS = 500;
/** Max catalog entries sent for mesocycle generation. */
export const MAX_MESO_CATALOG = 400;

const finite = z.number().finite();
const nonNegative = finite.min(0);
const count = Int32Schema.min(0);
const label = (max: number) => TextSchema.min(1).max(max);
const EXERCISE_NAME_MAX = 200;
const utf8Bytes = (s: string) => new TextEncoder().encode(s).byteLength;

// ── Coach snapshot: explicit allowlist mirroring CoachSnapshot (lib/coach-snapshot.ts) ──
// List caps mirror construirSnapshot (5 stalled, ≤12 muscle groups, 6 measurements); PRs are
// bounded generously. Photos, ids, urls and object keys can never appear.

const CoachBodySummarySchema = z.object({ actual: finite, delta4sem: finite.nullable() }).strict();

export const CoachSnapshotSchema = z
  .object({
    estancados: z
      .array(z.object({ ejercicio: label(EXERCISE_NAME_MAX), sesionesSinMejora: count }).strict())
      .max(5),
    semana: z
      .object({
        sesiones: count,
        objetivo: count,
        volumen: nonNegative,
        deltaPct: finite.nullable(),
        prs: z
          .array(z.object({ ejercicio: label(EXERCISE_NAME_MAX), tipo: z.enum(['peso', '1rm']) }).strict())
          .max(100),
      })
      .strict(),
    grupos: z
      .array(
        z
          .object({
            grupo: label(40),
            volumenSemana: nonNegative,
            diasSinEntrenar: count.nullable(),
            objetivo: nonNegative.nullable(),
          })
          .strict(),
      )
      .max(MUSCLE_GROUPS.length),
    cuerpo: z
      .object({
        peso: CoachBodySummarySchema.nullable(),
        medidas: z
          .array(z.object({ metrica: label(80), actual: finite, delta4sem: finite.nullable() }).strict())
          .max(6),
      })
      .strict(),
  })
  .strict()
  .refine((s) => utf8Bytes(JSON.stringify(s)) <= MAX_SNAPSHOT_BYTES, {
    message: `Snapshot exceeds ${MAX_SNAPSHOT_BYTES} bytes`,
  });
export type CoachSnapshotDto = z.infer<typeof CoachSnapshotSchema>;

// ── Coach ──

const nonBlank = (max: number) =>
  TextSchema.min(1).max(max).refine((s) => s.trim().length > 0, 'Must not be blank');

export const CoachRequestSchema = z
  .object({
    /** Client id of the user message: retries reuse it, so a response is never duplicated. */
    clientMessageId: z.uuid(),
    text: nonBlank(MAX_COACH_TEXT_CHARS),
    snapshot: CoachSnapshotSchema,
    // No client-supplied history: the server builds thread context from the user's own synced
    // coach_messages (lib/api/v1/ai/store.ts loadCoachContext).
  })
  .strict()
  .refine((r) => utf8Bytes(r.text) + utf8Bytes(JSON.stringify(r.snapshot)) <= MAX_COACH_REQUEST_BYTES, {
    message: `Coach request exceeds ${MAX_COACH_REQUEST_BYTES} bytes`,
  });
export type CoachRequest = z.infer<typeof CoachRequestSchema>;

// ── Mesocycle generation (ranges match the PWA generator, components/meso-generator.tsx) ──

export const MESO_OBJECTIVES = ['hipertrofia', 'fuerza', 'general'] as const;

export const MesocycleParamsSchema = z
  .object({
    objetivo: z.enum(MESO_OBJECTIVES),
    diasPorSemana: Int32Schema.min(2).max(6),
    semanas: Int32Schema.min(4).max(8),
    minutosPorSesion: Int32Schema.min(30).max(90),
  })
  .strict();

export const MesocycleRequestSchema = z
  .object({
    /** Idempotency key of the generation request. */
    clientRequestId: z.uuid(),
    params: MesocycleParamsSchema,
    snapshot: CoachSnapshotSchema,
    catalogo: z
      .array(
        z
          .object({
            nombre: label(EXERCISE_NAME_MAX),
            grupo: z.enum(MUSCLE_GROUPS as [MuscleGroup, ...MuscleGroup[]]),
            equipamiento: z.enum(EQUIPMENTS as [Equipment, ...Equipment[]]),
          })
          .strict(),
      )
      .max(MAX_MESO_CATALOG),
  })
  .strict();
export type MesocycleRequest = z.infer<typeof MesocycleRequestSchema>;

// ── Reporting a problematic AI response (POST /api/v1/ai/responses/:id/report) ──

/** Keep in sync with the CHECK constraint of reported_ai_responses (drizzle/0003). */
export const AI_REPORT_CATEGORIES = ['inaccurate', 'harmful', 'medical', 'offensive', 'other'] as const;
export type AiReportCategory = (typeof AI_REPORT_CATEGORIES)[number];

export const AiResponseIdSchema = z.uuid();

export const ReportResponseRequestSchema = z
  .object({
    category: z.enum(AI_REPORT_CATEGORIES),
    context: TextSchema.max(MAX_REPORT_CONTEXT_CHARS).optional(),
  })
  .strict();
export type ReportResponseRequest = z.infer<typeof ReportResponseRequestSchema>;

// ── Streaming events (newline-delimited JSON, one object per line: application/x-ndjson) ──

export const AiStreamEventSchema = z.discriminatedUnion('type', [
  // responseId is stable across retries of the same clientMessageId; the client stores the
  // assistant coach_message under this id.
  z.object({ type: z.literal('start'), clientMessageId: z.uuid(), responseId: AiResponseIdSchema }).strict(),
  z.object({ type: z.literal('delta'), text: z.string().max(MAX_STREAM_DELTA_CHARS) }).strict(),
  // disclaimer: the response is general training guidance, not medical advice; the client must
  // show the disclaimer with it.
  z.object({ type: z.literal('complete'), responseId: AiResponseIdSchema, disclaimer: z.literal(true) }).strict(),
  z.object({ type: z.literal('error'), code: z.string().regex(/^[A-Z_]{1,64}$/), retryable: z.boolean() }).strict(),
]);
export type AiStreamEvent = z.infer<typeof AiStreamEventSchema>;

// ── Mesocycle draft (response of POST /api/v1/ai/mesocycles) ──
// Validated on the server from the model's structured output (lib/mesocycle-draft.ts). Exercise names
// are EXACT names from the request's catalog: the client maps them to its own exercise ids. The server
// never returns ids for user data; the client creates every row with its own local ids.

export const MESO_DRAFT_LIMITS = {
  nameChars: 120,
  goalChars: 120,
  notesChars: 1000,
  adjustmentChars: 300,
  dayNameChars: 80,
  exercisesPerDay: 12,
  sets: { min: 1, max: 10 },
  reps: { min: 1, max: 50 },
  restSeconds: { min: 15, max: 600 },
  omittedNames: 50,
} as const;

const L = MESO_DRAFT_LIMITS;
const intIn = (r: { min: number; max: number }) => Int32Schema.min(r.min).max(r.max);

export const MesocycleDraftSchema = z
  .object({
    nombre: nonBlank(L.nameChars),
    objetivo: nonBlank(L.goalChars),
    semanas: Int32Schema.min(4).max(8),
    diasPorSemana: Int32Schema.min(2).max(6),
    notas: TextSchema.max(L.notesChars).nullable(),
    /** Exactly one entry per week, weeks 1..semanas in order. */
    progresion: z
      .array(z.object({ semana: Int32Schema.min(1).max(8), descarga: z.boolean(), ajuste: nonBlank(L.adjustmentChars) }).strict())
      .min(4)
      .max(8),
    /** Exactly diasPorSemana days, in training order. */
    dias: z
      .array(
        z
          .object({
            nombre: nonBlank(L.dayNameChars),
            ejercicios: z
              .array(
                z
                  .object({
                    nombre: label(EXERCISE_NAME_MAX),
                    seriesObjetivo: intIn(L.sets),
                    repsObjetivo: intIn(L.reps),
                    descansoSegundos: intIn(L.restSeconds),
                  })
                  .strict(),
              )
              .min(1)
              .max(L.exercisesPerDay),
          })
          .strict(),
      )
      .min(2)
      .max(6),
  })
  .strict()
  .refine((d) => d.progresion.length === d.semanas && d.progresion.every((p, i) => p.semana === i + 1), {
    message: 'progresion must cover weeks 1..semanas in order',
  })
  .refine((d) => d.dias.length === d.diasPorSemana, { message: 'dias must have diasPorSemana entries' });
export type MesocycleDraft = z.infer<typeof MesocycleDraftSchema>;

export const MesocycleResponseSchema = z
  .object({
    clientRequestId: z.uuid(),
    responseId: AiResponseIdSchema,
    draft: MesocycleDraftSchema,
    /** Exercises the model proposed that are not in the request's catalog (dropped from the draft). */
    omitidos: z.array(label(EXERCISE_NAME_MAX)).max(L.omittedNames),
    /** General training guidance, not medical advice: the client shows the disclaimer with it. */
    disclaimer: z.literal(true),
  })
  .strict();
export type MesocycleResponse = z.infer<typeof MesocycleResponseSchema>;

// ── Intelligent report (POST /api/v1/ai/reports) ──
// The source is the same allowlisted snapshot as the Coach (no photos, ids, URLs or keys). The server
// fingerprints it and returns the cached report for an identical source; any change regenerates.

export const IntelligentReportRequestSchema = z.object({ snapshot: CoachSnapshotSchema }).strict();
export type IntelligentReportRequest = z.infer<typeof IntelligentReportRequestSchema>;

export const REPORT_LIMITS = { summaryChars: 1500, itemChars: 300, items: 5 } as const;
const reportItems = z.array(nonBlank(REPORT_LIMITS.itemChars)).max(REPORT_LIMITS.items);

export const IntelligentReportSchema = z
  .object({
    resumen: nonBlank(REPORT_LIMITS.summaryChars),
    puntosFuertes: reportItems,
    aMejorar: reportItems,
    recomendaciones: reportItems.min(1),
  })
  .strict();
export type IntelligentReport = z.infer<typeof IntelligentReportSchema>;

export const IntelligentReportResponseSchema = z
  .object({
    reportId: z.string().min(1).max(128),
    /** When the report was generated (epoch ms); older than now when served from the cache. */
    generatedAt: z.number().int().min(0),
    cached: z.boolean(),
    report: IntelligentReportSchema,
    disclaimer: z.literal(true),
  })
  .strict();
export type IntelligentReportResponse = z.infer<typeof IntelligentReportResponseSchema>;
