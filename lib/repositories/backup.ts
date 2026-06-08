import { db } from '@/lib/db/database';
import type {
  Exercise, ExercisePhoto, Gym, Routine, RoutineExercise,
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
    routineExercises: RoutineExercise[];
    workoutSessions: WorkoutSession[];
    loggedExercises: LoggedExercise[];
    loggedSets: LoggedSet[];
    exercisePhotos: ExercisePhoto[];
  };
}

export async function exportData(): Promise<BackupFile> {
  return {
    app: 'gymlog',
    version: 6,
    exportedAt: Date.now(),
    data: {
      gyms: await db.gyms.toArray(),
      exercises: await db.exercises.toArray(),
      routines: await db.routines.toArray(),
      routineExercises: await db.routineExercises.toArray(),
      workoutSessions: await db.workoutSessions.toArray(),
      loggedExercises: await db.loggedExercises.toArray(),
      loggedSets: await db.loggedSets.toArray(),
      exercisePhotos: await db.exercisePhotos.toArray(),
    },
  };
}

export async function importData(backup: BackupFile): Promise<void> {
  if (!backup || backup.app !== 'gymlog' || !backup.data) {
    throw new Error('Fichero de copia no válido');
  }
  const d = backup.data;
  // Refresca updatedAt en todo lo importado: si las fechas del fichero son
  // anteriores a la marca de agua del sync, los registros quedarían "huérfanos"
  // y no se subirían nunca. Importar = "esto manda aquí", así que se re-sincroniza.
  const ts = Date.now();
  for (const arr of Object.values(d)) {
    for (const rec of arr as { updatedAt: number }[]) rec.updatedAt = ts;
  }
  const tables = [
    db.gyms, db.exercises, db.routines, db.routineExercises,
    db.workoutSessions, db.loggedExercises, db.loggedSets, db.exercisePhotos,
  ] as const;
  await db.transaction('rw', tables, async () => {
    if (d.gyms?.length) await db.gyms.bulkPut(d.gyms);
    if (d.exercises?.length) await db.exercises.bulkPut(d.exercises);
    if (d.routines?.length) await db.routines.bulkPut(d.routines);
    if (d.routineExercises?.length) await db.routineExercises.bulkPut(d.routineExercises);
    if (d.workoutSessions?.length) await db.workoutSessions.bulkPut(d.workoutSessions);
    if (d.loggedExercises?.length) await db.loggedExercises.bulkPut(d.loggedExercises);
    if (d.loggedSets?.length) await db.loggedSets.bulkPut(d.loggedSets);
    if (d.exercisePhotos?.length) await db.exercisePhotos.bulkPut(d.exercisePhotos);
  });
}
