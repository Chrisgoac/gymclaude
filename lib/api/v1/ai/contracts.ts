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
