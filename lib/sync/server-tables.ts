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
};
