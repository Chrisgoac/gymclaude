import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';

describe('GymLogDB', () => {
  beforeEach(async () => {
    await db.exercises.clear();
  });

  it('tiene la tabla exercises', () => {
    expect(db.exercises).toBeDefined();
  });

  it('persiste y recupera un ejercicio por id', async () => {
    await db.exercises.put({
      id: 'x1', userId: null, nombre: 'Press banca', grupoMuscular: 'pecho',
      equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false,
      updatedAt: 1, deletedAt: null,
    });
    const found = await db.exercises.get('x1');
    expect(found?.nombre).toBe('Press banca');
  });

  it('expone las tablas de rutinas (v2)', () => {
    expect(db.routines).toBeDefined();
    expect(db.routineExercises).toBeDefined();
  });

  it('expone las tablas de registro de entrenos (v3)', () => {
    expect(db.workoutSessions).toBeDefined();
    expect(db.loggedExercises).toBeDefined();
    expect(db.loggedSets).toBeDefined();
  });

  it('v5: tabla gyms operativa y workoutSessions admite gymId', async () => {
    await db.gyms.clear();
    await db.gyms.put({
      id: 'g1', userId: null, nombre: 'Gold\'s', orden: 0, archivada: false,
      updatedAt: Date.now(), deletedAt: null,
    });
    expect((await db.gyms.get('g1'))?.nombre).toBe("Gold's");

    await db.workoutSessions.clear();
    await db.workoutSessions.put({
      id: 's1', userId: null, gymId: 'g1', fecha: Date.now(),
      updatedAt: Date.now(), deletedAt: null,
    });
    const porGym = await db.workoutSessions.where('gymId').equals('g1').toArray();
    expect(porGym.map((s) => s.id)).toEqual(['s1']);
  });
});
