import { pgTable, text, doublePrecision, integer, bigint, boolean, primaryKey } from 'drizzle-orm/pg-core';

// Columnas comunes de sincronización en cada tabla.
const sync = {
  id: text('id').primaryKey(),
  userId: text('user_id').notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(), // reloj del cliente (LWW)
  deletedAt: bigint('deleted_at', { mode: 'number' }), // tombstone (nullable)
  serverUpdatedAt: bigint('server_updated_at', { mode: 'number' }).notNull(), // reloj del servidor (cursor de pull)
};

export const exercises = pgTable('exercises', {
  ...sync,
  nombre: text('nombre').notNull(),
  grupoMuscular: text('grupo_muscular').notNull(),
  equipamiento: text('equipamiento').notNull(),
  tipo: text('tipo').notNull(),
  videoUrl: text('video_url'),
  notas: text('notas'),
  esPersonalizado: boolean('es_personalizado').notNull(),
});

export const routines = pgTable('routines', {
  ...sync,
  nombre: text('nombre').notNull(),
  descripcion: text('descripcion'),
  orden: integer('orden'),
  archivada: boolean('archivada').notNull(),
});

export const routineExercises = pgTable('routine_exercises', {
  ...sync,
  routineId: text('routine_id'),
  exerciseId: text('exercise_id').notNull(),
  orden: integer('orden').notNull(),
  seriesObjetivo: integer('series_objetivo'),
  repsObjetivo: integer('reps_objetivo'),
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
  key: text('key').notNull(),
});

export const userSettings = pgTable('user_settings', {
  id: text('id').notNull(),
  userId: text('user_id').notNull(),
  valor: text('valor').notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(),
  deletedAt: bigint('deleted_at', { mode: 'number' }),
  serverUpdatedAt: bigint('server_updated_at', { mode: 'number' }).notNull(),
}, (t) => ({
  pk: primaryKey({ columns: [t.userId, t.id] }),
}));

export const coachMessages = pgTable('coach_messages', {
  ...sync,
  rol: text('rol').notNull(),
  contenido: text('contenido').notNull(),
  createdAt: bigint('created_at', { mode: 'number' }).notNull(),
});
