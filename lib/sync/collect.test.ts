import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { createExercise } from '@/lib/repositories/exercises';
import { createRoutine } from '@/lib/repositories/routines';
import { collectDirty } from '@/lib/sync/collect';

beforeEach(async () => {
  await Promise.all([db.exercises.clear(), db.routines.clear(), db.gyms.clear(), db.exercisePhotos.clear(), db.userSettings.clear()]);
});

describe('collectDirty', () => {
  it('recoge registros con updatedAt > marca de agua', async () => {
    await createRoutine({ nombre: 'Vieja' });
    const changes = await collectDirty(0);
    const rutinas = changes.find((c) => c.table === 'routines');
    expect(rutinas?.records).toHaveLength(1);
  });

  it('excluye lo anterior a la marca de agua', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const changes = await collectDirty(r.updatedAt);
    expect(changes.find((c) => c.table === 'routines')).toBeUndefined();
  });

  it('sincroniza los gimnasios', async () => {
    await db.gyms.clear();
    await db.gyms.put({
      id: 'g1', userId: null, nombre: 'Gold\'s', orden: 0, archivada: false,
      updatedAt: Date.now(), deletedAt: null,
    });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'gyms')?.records).toHaveLength(1);
  });

  it('sincroniza las fotos de ejercicio', async () => {
    await db.exercisePhotos.clear();
    await db.exercisePhotos.put({
      id: 'p1', userId: null, exerciseId: 'seed-press-banca',
      url: 'https://pub.r2.dev/p1.jpg', key: 'u/seed-press-banca/p1.jpg',
      updatedAt: Date.now(), deletedAt: null,
    });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'exercisePhotos')?.records).toHaveLength(1);
  });

  it('sincroniza userSettings sin filtro shouldSync', async () => {
    await db.userSettings.clear();
    await db.userSettings.put({
      id: 'modoProgresion', userId: null, valor: '"doble"',
      updatedAt: 1000, deletedAt: null,
    });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'userSettings')?.records).toHaveLength(1);
  });

  it('sincroniza coachMessages', async () => {
    await db.coachMessages.clear();
    await db.coachMessages.put({ id: 'cm1', userId: null, rol: 'user', contenido: 'q', createdAt: 1, updatedAt: 1000, deletedAt: null });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'coachMessages')?.records).toHaveLength(1);
  });

  it('sincroniza bodyMetrics', async () => {
    await db.bodyMetrics.clear();
    await db.bodyMetrics.put({ id: 'bm-c1', userId: null, tipo: 'peso', valor: 80, fecha: 1, updatedAt: 1000, deletedAt: null });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'bodyMetrics')?.records).toHaveLength(1);
  });

  it('sincroniza progressPhotos', async () => {
    await db.progressPhotos.clear();
    await db.progressPhotos.put({ id: 'pp-c1', userId: null, url: 'u', key: 'k', fecha: 1, angulo: 'lado', nota: null, updatedAt: 1000, deletedAt: null });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'progressPhotos')?.records).toHaveLength(1);
  });

  it('sincroniza mesocycles', async () => {
    await db.mesocycles.clear();
    await db.mesocycles.put({ id: 'me-c1', userId: null, nombre: 'H', objetivo: 'fuerza', semanas: 4, diasPorSemana: 3, notas: null, progresion: [], fechaInicio: 1, updatedAt: 1000, deletedAt: null });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'mesocycles')?.records).toHaveLength(1);
  });

  it('sincroniza achievements', async () => {
    await db.achievements.clear();
    await db.achievements.put({ id: 'a-c1', userId: null, clave: 'racha-4', fechaDesbloqueo: 1, updatedAt: 1000, deletedAt: null });
    const changes = await collectDirty(0);
    expect(changes.find((c) => c.table === 'achievements')?.records).toHaveLength(1);
  });

  it('sólo sincroniza ejercicios personalizados, no los del catálogo (seed)', async () => {
    await db.exercises.put({
      id: 'seed-x', userId: null, nombre: 'Seed', grupoMuscular: 'pecho',
      equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: Date.now(), deletedAt: null,
    });
    await createExercise({ nombre: 'Mío', grupoMuscular: 'biceps', equipamiento: 'mancuerna', tipo: 'aislamiento' });
    const changes = await collectDirty(0);
    const ej = changes.find((c) => c.table === 'exercises');
    expect(ej?.records.map((r) => (r as unknown as { nombre: string }).nombre)).toEqual(['Mío']);
  });
});
