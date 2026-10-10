import type { PgTable } from 'drizzle-orm/pg-core';
import * as schema from '@/db/schema';

export const SERVER_TABLES: Record<string, PgTable> = {
  exercises: schema.exercises,
  routines: schema.routines,
  routineExercises: schema.routineExercises,
  workoutSessions: schema.workoutSessions,
  loggedExercises: schema.loggedExercises,
  loggedSets: schema.loggedSets,
  gyms: schema.gyms,
  exercisePhotos: schema.exercisePhotos,
  userSettings: schema.userSettings,
  coachMessages: schema.coachMessages,
  bodyMetrics: schema.bodyMetrics,
  progressPhotos: schema.progressPhotos,
  mesocycles: schema.mesocycles,
  achievements: schema.achievements,
};

/**
 * Quita los campos solo-servidor (cursor legacy + metadatos v1). La sync legacy los descarta al
 * leer y al escribir: el cliente nunca los ve ni puede fijarlos.
 */
export function withoutServerMeta<T extends Record<string, unknown>>(rec: T) {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { serverUpdatedAt, serverVersion, lastDeviceId, lastMutationId, ...rest } = rec;
  return rest;
}
