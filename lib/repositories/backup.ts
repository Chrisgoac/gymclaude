import { db } from '@/lib/db/database';
import type {
  Exercise, Gym, Routine, RoutineDay, RoutineExercise,
  WorkoutSession, LoggedExercise, LoggedSet,
} from '@/lib/db/types';

export interface BackupFile {
  app: 'gymlog';
  version: number;
  exportedAt: number;
  data: {
    gyms: Gym[];
    exercises: Exercise[];
    routines: Routine[];
    routineDays: RoutineDay[];
    routineExercises: RoutineExercise[];
    workoutSessions: WorkoutSession[];
    loggedExercises: LoggedExercise[];
    loggedSets: LoggedSet[];
  };
}

export async function exportData(): Promise<BackupFile> {
  return {
    app: 'gymlog',
    version: 4,
    exportedAt: Date.now(),
    data: {
      gyms: await db.gyms.toArray(),
      exercises: await db.exercises.toArray(),
      routines: await db.routines.toArray(),
      routineDays: await db.routineDays.toArray(),
      routineExercises: await db.routineExercises.toArray(),
      workoutSessions: await db.workoutSessions.toArray(),
      loggedExercises: await db.loggedExercises.toArray(),
      loggedSets: await db.loggedSets.toArray(),
    },
  };
}

export async function importData(backup: BackupFile): Promise<void> {
  if (!backup || backup.app !== 'gymlog' || !backup.data) {
    throw new Error('Fichero de copia no válido');
  }
  const d = backup.data;
  const tables = [
    db.gyms, db.exercises, db.routines, db.routineDays, db.routineExercises,
    db.workoutSessions, db.loggedExercises, db.loggedSets,
  ] as const;
  await db.transaction('rw', tables, async () => {
    if (d.gyms?.length) await db.gyms.bulkPut(d.gyms);
    if (d.exercises?.length) await db.exercises.bulkPut(d.exercises);
    if (d.routines?.length) await db.routines.bulkPut(d.routines);
    if (d.routineDays?.length) await db.routineDays.bulkPut(d.routineDays);
    if (d.routineExercises?.length) await db.routineExercises.bulkPut(d.routineExercises);
    if (d.workoutSessions?.length) await db.workoutSessions.bulkPut(d.workoutSessions);
    if (d.loggedExercises?.length) await db.loggedExercises.bulkPut(d.loggedExercises);
    if (d.loggedSets?.length) await db.loggedSets.bulkPut(d.loggedSets);
  });
}
