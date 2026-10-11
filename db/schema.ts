import { pgTable, text, doublePrecision, integer, bigint, bigserial, boolean, jsonb, primaryKey, index } from 'drizzle-orm/pg-core';

// Metadatos solo-servidor de la sync v1. Nunca llegan del cliente.
// serverVersion = sync_changes.sequence de la última escritura (nullable por compatibilidad con
// escritores legacy; la migración 0001 rellena todas las filas existentes).
function serverMeta() {
  return {
    serverVersion: bigint('server_version', { mode: 'bigint' }),
    lastDeviceId: text('last_device_id'),
    lastMutationId: text('last_mutation_id'),
  };
}

// Columnas comunes de sincronización en cada tabla.
const sync = {
  id: text('id').primaryKey(),
  userId: text('user_id').notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(), // reloj del cliente (LWW)
  deletedAt: bigint('deleted_at', { mode: 'number' }), // tombstone (nullable)
  serverUpdatedAt: bigint('server_updated_at', { mode: 'number' }).notNull(), // reloj del servidor (cursor de pull)
  ...serverMeta(),
};

export const exercises = pgTable('exercises', {
  ...sync,
  nombre: text('nombre').notNull(),
  grupoMuscular: text('grupo_muscular').notNull(),
  equipamiento: text('equipamiento').notNull(),
  tipo: text('tipo').notNull(),
  videoUrl: text('video_url'),
  notas: text('notas'),
  descripcion: text('descripcion'),
  execution: text('execution'),
  incrementoKg: doublePrecision('incremento_kg'),
  esPersonalizado: boolean('es_personalizado').notNull(),
});

export const routines = pgTable('routines', {
  ...sync,
  nombre: text('nombre').notNull(),
  descripcion: text('descripcion'),
  orden: integer('orden'),
  archivada: boolean('archivada').notNull(),
  mesocycleId: text('mesocycle_id'),
});

export const routineExercises = pgTable('routine_exercises', {
  ...sync,
  routineId: text('routine_id'),
  exerciseId: text('exercise_id').notNull(),
  orden: integer('orden').notNull(),
  seriesObjetivo: integer('series_objetivo'),
  repsObjetivo: integer('reps_objetivo'),
  repsObjetivoMin: integer('reps_objetivo_min'),
  descansoSegundos: integer('descanso_segundos'),
  notas: text('notas'),
});

export const workoutSessions = pgTable('workout_sessions', {
  ...sync,
  routineDayId: text('routine_day_id'),
  routineId: text('routine_id'),
  gymId: text('gym_id'),
  fecha: bigint('fecha', { mode: 'number' }).notNull(),
  duracionSegundos: integer('duracion_segundos'),
  notas: text('notas'),
});

export const loggedExercises = pgTable('logged_exercises', {
  ...sync,
  sessionId: text('session_id').notNull(),
  exerciseId: text('exercise_id').notNull(),
  orden: integer('orden').notNull(),
});

export const loggedSets = pgTable('logged_sets', {
  ...sync,
  loggedExerciseId: text('logged_exercise_id').notNull(),
  orden: integer('orden').notNull(),
  peso: doublePrecision('peso').notNull(),
  reps: integer('reps').notNull(),
  esCalentamiento: boolean('es_calentamiento'),
});

export const gyms = pgTable('gyms', {
  ...sync,
  nombre: text('nombre').notNull(),
  orden: integer('orden').notNull(),
  archivada: boolean('archivada').notNull(),
});

export const exercisePhotos = pgTable('exercise_photos', {
  ...sync,
  exerciseId: text('exercise_id').notNull(),
  url: text('url').notNull(),
  key: text('key').notNull(), // clave del objeto R2 (objectKey)
  // Metadatos de medios privados v1 (0002). Nulos en filas legacy de la PWA.
  mime: text('mime'),
  bytes: integer('bytes'),
  width: integer('width'),
  height: integer('height'),
});

export const userSettings = pgTable('user_settings', {
  id: text('id').notNull(),
  userId: text('user_id').notNull(),
  valor: text('valor').notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(),
  deletedAt: bigint('deleted_at', { mode: 'number' }),
  serverUpdatedAt: bigint('server_updated_at', { mode: 'number' }).notNull(),
  ...serverMeta(),
}, (t) => ({
  pk: primaryKey({ columns: [t.userId, t.id] }),
}));

export const coachMessages = pgTable('coach_messages', {
  ...sync,
  rol: text('rol').notNull(),
  contenido: text('contenido').notNull(),
  createdAt: bigint('created_at', { mode: 'number' }).notNull(),
});

export const bodyMetrics = pgTable('body_metrics', {
  ...sync,
  tipo: text('tipo').notNull(),
  valor: doublePrecision('valor').notNull(),
  fecha: bigint('fecha', { mode: 'number' }).notNull(),
});

export const progressPhotos = pgTable('progress_photos', {
  ...sync,
  url: text('url').notNull(),
  key: text('key').notNull(),
  fecha: bigint('fecha', { mode: 'number' }).notNull(),
  angulo: text('angulo').notNull(),
  nota: text('nota'),
  // Metadatos de medios privados v1 (0002). Nulos en filas legacy de la PWA.
  mime: text('mime'),
  bytes: integer('bytes'),
  width: integer('width'),
  height: integer('height'),
});

export const mesocycles = pgTable('mesocycles', {
  ...sync,
  nombre: text('nombre').notNull(),
  objetivo: text('objetivo').notNull(),
  semanas: integer('semanas').notNull(),
  diasPorSemana: integer('dias_por_semana').notNull(),
  notas: text('notas'),
  progresion: jsonb('progresion').notNull(),
  fechaInicio: bigint('fecha_inicio', { mode: 'number' }).notNull(),
});

export const achievements = pgTable('achievements', {
  ...sync,
  clave: text('clave').notNull(),
  fechaDesbloqueo: bigint('fecha_desbloqueo', { mode: 'number' }).notNull(),
});

// ── Infraestructura de la sync v1 (solo servidor, no se sincroniza) ──

// Dedupe de mutaciones: la respuesta guardada se repite tal cual en reintentos.
export const syncMutations = pgTable('sync_mutations', {
  userId: text('user_id').notNull(),
  mutationId: text('mutation_id').notNull(),
  responseJson: jsonb('response_json').notNull(),
  processedAt: bigint('processed_at', { mode: 'number' }).notNull(), // epoch ms
}, (t) => [primaryKey({ columns: [t.userId, t.mutationId] })]);

// Diario global de cambios: `sequence` es la única fuente de orden del pull.
export const syncChanges = pgTable('sync_changes', {
  sequence: bigserial('sequence', { mode: 'bigint' }).primaryKey(),
  userId: text('user_id').notNull(),
  tableName: text('table_name').notNull(),
  recordId: text('record_id').notNull(),
  changedAt: bigint('changed_at', { mode: 'number' }).notNull(), // epoch ms
}, (t) => [index('sync_changes_user_sequence_idx').on(t.userId, t.sequence)]);

// Contadores de rate-limit compartidos entre instancias (ventana fija).
export const apiRateBuckets = pgTable('api_rate_buckets', {
  scope: text('scope').notNull(),
  subjectHash: text('subject_hash').notNull(),
  windowStart: bigint('window_start', { mode: 'number' }).notNull(), // epoch ms
  count: integer('count').notNull(),
}, (t) => [primaryKey({ columns: [t.scope, t.subjectHash, t.windowStart] })]);

// Borrados diferidos de objetos R2 tras el tombstone de una foto (0002). Solo servidor: no se
// sincroniza. `due_at` es a la vez la fecha de vencimiento y el lease del proceso que la reclama.
export const mediaDeletions = pgTable('media_deletions', {
  objectKey: text('object_key').primaryKey(),
  userId: text('user_id').notNull(),
  dueAt: bigint('due_at', { mode: 'number' }).notNull(), // epoch ms
  attempts: integer('attempts').notNull().default(0),
  lastError: text('last_error'),
  createdAt: bigint('created_at', { mode: 'number' }).notNull(), // epoch ms
}, (t) => [index('media_deletions_due_at_idx').on(t.dueAt)]);
