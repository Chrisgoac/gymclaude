import { it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { createRoutine, addExerciseToRoutine } from '@/lib/repositories/routines';
import { startSession, addLoggedExercise, addSet } from '@/lib/repositories/workouts';
import { exportData, importData } from '@/lib/repositories/backup';

beforeEach(async () => {
  await Promise.all([
    db.exercises.clear(), db.routines.clear(), db.routineExercises.clear(),
    db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(),
    db.gyms.clear(),
  ]);
});

it('exporta e importa todos los datos (roundtrip)', async () => {
  const r = await createRoutine({ nombre: 'R' });
  await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
  const s = await startSession({});
  const le = await addLoggedExercise(s.id, 'seed-press-banca');
  await addSet(le.id, { peso: 60, reps: 8 });

  const backup = await exportData();
  expect(backup.app).toBe('gymlog');
  expect(backup.data.routines).toHaveLength(1);

  await Promise.all([
    db.routines.clear(), db.workoutSessions.clear(),
    db.loggedExercises.clear(), db.loggedSets.clear(),
  ]);
  expect(await db.routines.count()).toBe(0);

  await importData(backup);
  expect(await db.routines.count()).toBe(1);
  expect(await db.routineExercises.count()).toBe(1);
  expect(await db.loggedSets.count()).toBe(1);
});

it('exporta e importa los gimnasios', async () => {
  await db.gyms.clear();
  await db.gyms.put({
    id: 'g1', userId: null, nombre: 'Gold\'s', orden: 0, archivada: false,
    updatedAt: Date.now(), deletedAt: null,
  });
  const backup = await exportData();
  expect(backup.data.gyms).toHaveLength(1);
  await db.gyms.clear();
  await importData(backup);
  expect((await db.gyms.get('g1'))?.nombre).toBe("Gold's");
});

it('rechaza un fichero no válido', async () => {
  await expect(importData({ app: 'otra-cosa' } as never)).rejects.toThrow();
});
