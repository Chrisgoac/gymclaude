import { z } from 'zod';
import {
  DeviceIdSchema,
  EpochMsSchema,
  Int32Schema,
  JsonValueSchema,
  MutationIdSchema,
  RecordIdSchema,
  ServerVersionSchema,
  TextSchema,
} from './common';

export { ApiErrorBodySchema, type ApiErrorBody } from './common';

export const SYNC_TABLE_NAMES = [
  'exercises', 'routines', 'routineExercises', 'workoutSessions',
  'loggedExercises', 'loggedSets', 'gyms', 'exercisePhotos',
  'userSettings', 'coachMessages', 'bodyMetrics', 'progressPhotos',
  'mesocycles', 'achievements',
] as const;
export const SyncTableNameSchema = z.enum(SYNC_TABLE_NAMES);
export type SyncTableName = z.infer<typeof SyncTableNameSchema>;

// Common record columns. Records never carry userId, serverVersion or serverUpdatedAt.
const base = {
  id: RecordIdSchema,
  updatedAt: EpochMsSchema,
  deletedAt: EpochMsSchema.nullable(),
};
const str = TextSchema;
const int = Int32Schema;
const num = z.number().finite();
const opt = <T extends z.ZodType>(s: T) => s.nullish();

const record = <S extends z.ZodRawShape>(shape: S) => z.object({ ...base, ...shape }).strict();

export const RecordSchemas = {
  exercises: record({
    nombre: str, grupoMuscular: str, equipamiento: str, tipo: str,
    videoUrl: opt(str), notas: opt(str), descripcion: opt(str), execution: opt(str),
    incrementoKg: opt(num), esPersonalizado: z.boolean(),
  }),
  routines: record({
    nombre: str, descripcion: opt(str), orden: opt(int), archivada: z.boolean(), mesocycleId: opt(str),
  }),
  routineExercises: record({
    routineId: opt(str), exerciseId: str, orden: int, seriesObjetivo: opt(int),
    repsObjetivo: opt(int), repsObjetivoMin: opt(int), descansoSegundos: opt(int), notas: opt(str),
  }),
  workoutSessions: record({
    routineDayId: opt(str), routineId: opt(str), gymId: opt(str), fecha: EpochMsSchema,
    duracionSegundos: opt(int), notas: opt(str),
  }),
  loggedExercises: record({ sessionId: str, exerciseId: str, orden: int }),
  loggedSets: record({
    loggedExerciseId: str, orden: int, peso: num, reps: int, esCalentamiento: opt(z.boolean()),
  }),
  gyms: record({ nombre: str, orden: int, archivada: z.boolean() }),
  exercisePhotos: record({ exerciseId: str, url: str, key: str }),
  userSettings: record({ valor: str }),
  coachMessages: record({ rol: str, contenido: str, createdAt: EpochMsSchema }),
  bodyMetrics: record({ tipo: str, valor: num, fecha: EpochMsSchema }),
  progressPhotos: record({
    url: str, key: str, fecha: EpochMsSchema, angulo: str, nota: opt(str),
  }),
  mesocycles: record({
    nombre: str, objetivo: str, semanas: int, diasPorSemana: int, notas: opt(str),
    progresion: JsonValueSchema, fechaInicio: EpochMsSchema,
  }),
  achievements: record({ clave: str, fechaDesbloqueo: EpochMsSchema }),
} satisfies Record<SyncTableName, z.ZodType>;

const perTable = <T extends SyncTableName, E extends z.ZodRawShape>(
  table: T,
  extra: E,
) => z.object({ table: z.literal(table), record: RecordSchemas[table], ...extra }).strict();

const operationFor = <T extends SyncTableName>(t: T) =>
  perTable(t, { baseServerVersion: ServerVersionSchema.nullable() });
const changeFor = <T extends SyncTableName>(t: T) =>
  perTable(t, { serverVersion: ServerVersionSchema });

export const SyncOperationSchema = z.discriminatedUnion('table', [
  operationFor('exercises'), operationFor('routines'), operationFor('routineExercises'),
  operationFor('workoutSessions'), operationFor('loggedExercises'), operationFor('loggedSets'),
  operationFor('gyms'), operationFor('exercisePhotos'), operationFor('userSettings'),
  operationFor('coachMessages'), operationFor('bodyMetrics'), operationFor('progressPhotos'),
  operationFor('mesocycles'), operationFor('achievements'),
]);

/** Server -> client change: full record plus the row's serverVersion. */
export const SyncChangeSchema = z.discriminatedUnion('table', [
  changeFor('exercises'), changeFor('routines'), changeFor('routineExercises'),
  changeFor('workoutSessions'), changeFor('loggedExercises'), changeFor('loggedSets'),
  changeFor('gyms'), changeFor('exercisePhotos'), changeFor('userSettings'),
  changeFor('coachMessages'), changeFor('bodyMetrics'), changeFor('progressPhotos'),
  changeFor('mesocycles'), changeFor('achievements'),
]);

export const MAX_MUTATIONS_PER_PUSH = 50;
export const MAX_OPERATIONS_PER_MUTATION = 50;

export const PushMutationSchema = z
  .object({
    mutationId: MutationIdSchema,
    deviceId: DeviceIdSchema,
    operations: z.array(SyncOperationSchema).min(1).max(MAX_OPERATIONS_PER_MUTATION),
  })
  .strict()
  .superRefine((m, ctx) => {
    // A mutation writes each record at most once: two operations on one record are ambiguous.
    const seen = new Set<string>();
    m.operations.forEach((op, i) => {
      const key = `${op.table}\u0000${op.record.id}`;
      if (seen.has(key)) {
        ctx.addIssue({ code: 'custom', path: ['operations', i, 'record', 'id'], message: 'Duplicate (table, record.id) in mutation' });
      }
      seen.add(key);
    });
  });

export const PushRequestSchema = z
  .object({ mutations: z.array(PushMutationSchema).min(1).max(MAX_MUTATIONS_PER_PUSH) })
  .strict()
  .superRefine((req, ctx) => {
    const seen = new Set<string>();
    req.mutations.forEach((m, i) => {
      if (seen.has(m.mutationId)) {
        ctx.addIssue({ code: 'custom', path: ['mutations', i, 'mutationId'], message: 'Duplicate mutationId in request' });
      }
      seen.add(m.mutationId);
    });
  });

export const SyncConflictSchema = z
  .object({
    mutationId: MutationIdSchema,
    table: SyncTableNameSchema,
    recordId: RecordIdSchema,
    serverVersion: ServerVersionSchema,
    reason: z.string().min(1),
  })
  .strict();

export const PushResponseSchema = z
  .object({
    acceptedMutationIds: z.array(MutationIdSchema),
    conflicts: z.array(SyncConflictSchema),
  })
  .strict();

export const PullResponseSchema = z
  .object({
    changes: z.array(SyncChangeSchema),
    /** Opaque base64url text; decoded only on the server. */
    cursor: z.string().regex(/^[A-Za-z0-9_-]*$/),
    hasMore: z.boolean(),
  })
  .strict();

export type SyncOperationDto = z.infer<typeof SyncOperationSchema>;
export type SyncChangeDto = z.infer<typeof SyncChangeSchema>;
export type PushMutationDto = z.infer<typeof PushMutationSchema>;
export type PushRequest = z.infer<typeof PushRequestSchema>;
export type SyncConflictDto = z.infer<typeof SyncConflictSchema>;
export type PushResponse = z.infer<typeof PushResponseSchema>;
export type PullResponse = z.infer<typeof PullResponseSchema>;
