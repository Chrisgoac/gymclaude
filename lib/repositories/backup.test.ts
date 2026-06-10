import { it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { createRoutine, addExerciseToRoutine } from '@/lib/repositories/routines';
import { startSession, addLoggedExercise, addSet } from '@/lib/repositories/workouts';
import { exportData, importData } from '@/lib/repositories/backup';

beforeEach(async () => {
  await Promise.all([
    db.exercises.clear(), db.routines.clear(), db.routineExercises.clear(),
    db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(),
    db.gyms.clear(), db.exercisePhotos.clear(),
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

it('exporta e importa las fotos de ejercicio', async () => {
  await db.exercisePhotos.clear();
  await db.exercisePhotos.put({
    id: 'p1', userId: null, exerciseId: 'seed-press-banca',
    url: 'https://pub.r2.dev/x.jpg', key: 'u/e/x.jpg', updatedAt: Date.now(), deletedAt: null,
  });
  const backup = await exportData();
  expect(backup.data.exercisePhotos).toHaveLength(1);
  await db.exercisePhotos.clear();
  await importData(backup);
  expect(await db.exercisePhotos.count()).toBe(1);
});

it('al importar refresca updatedAt para que el sync los suba', async () => {
  const antiguo = 1000; // fecha vieja, anterior a cualquier marca de agua
  const backup = {
    app: 'gymlog' as const,
    version: 6,
    exportedAt: antiguo,
    data: {
      gyms: [], exercises: [],
      routines: [{ id: 'r1', userId: null, nombre: 'R', orden: 0, archivada: false, updatedAt: antiguo, deletedAt: null }],
      routineExercises: [{ id: 're1', routineId: 'r1', exerciseId: 'seed-press-banca', orden: 0, updatedAt: antiguo, deletedAt: null }],
      workoutSessions: [], loggedExercises: [], loggedSets: [], exercisePhotos: [], userSettings: [], coachMessages: [], bodyMetrics: [], progressPhotos: [], mesocycles: [], achievements: [],
    },
  };
  const t0 = Date.now();
  await importData(backup);
  const r = await db.routines.get('r1');
  const re = await db.routineExercises.get('re1');
  expect(r!.updatedAt).toBeGreaterThanOrEqual(t0);
  expect(re!.updatedAt).toBeGreaterThanOrEqual(t0);
});

it('rechaza un fichero no válido', async () => {
  await expect(importData({ app: 'otra-cosa' } as never)).rejects.toThrow();
});

it('exporta e importa userSettings', async () => {
  await db.userSettings.clear();
  await db.userSettings.put({ id: 'objetivoSemanal', userId: null, valor: '4', updatedAt: 1, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.userSettings).toHaveLength(1);
  await db.userSettings.clear();
  await importData(backup);
  const row = await db.userSettings.get('objetivoSemanal');
  expect(row?.valor).toBe('4');
});

it('exporta e importa coachMessages', async () => {
  await db.coachMessages.clear();
  await db.coachMessages.put({ id: 'b1', userId: null, rol: 'assistant', contenido: 'hey', createdAt: 5, updatedAt: 5, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.coachMessages).toHaveLength(1);
  await db.coachMessages.clear();
  await importData(backup);
  expect((await db.coachMessages.get('b1'))?.contenido).toBe('hey');
});

it('exporta e importa bodyMetrics', async () => {
  await db.bodyMetrics.clear();
  await db.bodyMetrics.put({ id: 'bk1', userId: null, tipo: 'peso', valor: 79.5, fecha: 5, updatedAt: 5, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.bodyMetrics).toHaveLength(1);
  await db.bodyMetrics.clear();
  await importData(backup);
  expect((await db.bodyMetrics.get('bk1'))?.valor).toBe(79.5);
});

it('exporta e importa progressPhotos', async () => {
  await db.progressPhotos.clear();
  await db.progressPhotos.put({ id: 'bk1', userId: null, url: 'u', key: 'k', fecha: 5, angulo: 'frente', nota: 'x', updatedAt: 5, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.progressPhotos).toHaveLength(1);
  await db.progressPhotos.clear();
  await importData(backup);
  expect((await db.progressPhotos.get('bk1'))?.key).toBe('k');
});

it('exporta e importa mesocycles', async () => {
  await db.mesocycles.clear();
  await db.mesocycles.put({ id: 'mb1', userId: null, nombre: 'H', objetivo: 'general', semanas: 5, diasPorSemana: 3, notas: null, progresion: [{ semana: 1, descarga: false, ajuste: 'x' }], fechaInicio: 5, updatedAt: 5, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.mesocycles).toHaveLength(1);
  await db.mesocycles.clear();
  await importData(backup);
  expect((await db.mesocycles.get('mb1'))?.nombre).toBe('H');
});

it('exporta e importa achievements', async () => {
  await db.achievements.clear();
  await db.achievements.put({ id: 'ab1', userId: null, clave: 'mesociclo-1', fechaDesbloqueo: 9, updatedAt: 9, deletedAt: null });
  const backup = await exportData();
  expect(backup.data.achievements).toHaveLength(1);
  await db.achievements.clear();
  await importData(backup);
  expect((await db.achievements.get('ab1'))?.clave).toBe('mesociclo-1');
});
