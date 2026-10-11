import { z } from 'zod';
import { EQUIPMENTS, MUSCLE_GROUPS, type Equipment, type MuscleGroup } from '@/lib/db/types';
import { Int32Schema, TextSchema } from '@/lib/api/v1/contracts/common';

// Wire contract of /api/v1/ai/*. Every object is strict: the client never supplies userId,
// entitlement state, model, or prompt text beyond the bounded fields below.

/** Max user message length for the Coach (chars). */
export const MAX_COACH_TEXT_CHARS = 2000;
/** Max prior turns sent with a Coach message, and max length of each. */
export const MAX_COACH_HISTORY_TURNS = 20;
export const MAX_COACH_HISTORY_TEXT_CHARS = 4000;
/** Max serialized (UTF-8 JSON) size of a Coach snapshot. */
export const MAX_SNAPSHOT_BYTES = 32 * 1024;
/** Max optional free-text context on a response report (chars). */
export const MAX_REPORT_CONTEXT_CHARS = 500;
/** Max catalog entries sent for mesocycle generation. */
export const MAX_MESO_CATALOG = 400;

const finite = z.number().finite();
const nonNegative = finite.min(0);
const count = Int32Schema.min(0);
const label = (max: number) => TextSchema.min(1).max(max);
const EXERCISE_NAME_MAX = 200;

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
  .refine((s) => new TextEncoder().encode(JSON.stringify(s)).byteLength <= MAX_SNAPSHOT_BYTES, {
    message: `Snapshot exceeds ${MAX_SNAPSHOT_BYTES} bytes`,
  });
export type CoachSnapshotDto = z.infer<typeof CoachSnapshotSchema>;

// ── Coach ──

const nonBlank = (max: number) =>
  TextSchema.min(1).max(max).refine((s) => s.trim().length > 0, 'Must not be blank');

export const CoachHistoryTurnSchema = z
  .object({ role: z.enum(['user', 'assistant']), text: nonBlank(MAX_COACH_HISTORY_TEXT_CHARS) })
  .strict();

export const CoachRequestSchema = z
  .object({
    /** Client id of the user message: retries reuse it, so a response is never duplicated. */
    clientMessageId: z.uuid(),
    text: nonBlank(MAX_COACH_TEXT_CHARS),
    snapshot: CoachSnapshotSchema,
    /** Prior turns of the persistent thread, oldest first. */
    history: z.array(CoachHistoryTurnSchema).max(MAX_COACH_HISTORY_TURNS).optional(),
  })
  .strict();
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

// ── Streaming events (one JSON object per event) ──

export const AiStreamEventSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('start'), clientMessageId: z.uuid() }).strict(),
  z.object({ type: z.literal('delta'), text: z.string() }).strict(),
  z.object({ type: z.literal('complete'), responseId: AiResponseIdSchema }).strict(),
  z.object({ type: z.literal('error'), code: z.string().min(1), retryable: z.boolean() }).strict(),
]);
export type AiStreamEvent = z.infer<typeof AiStreamEventSchema>;
