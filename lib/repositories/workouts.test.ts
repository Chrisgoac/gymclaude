import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { createRoutine, addDay, addExerciseToDay } from '@/lib/repositories/routines';
import {
  startSession, getSession, listSessions, finishSession, softDeleteSession,
  addLoggedExercise, listSessionExercises, softDeleteLoggedExercise,
  addSet, updateSet, softDeleteSet, listExerciseSets, getLastSet,
} from '@/lib/repositories/workouts';

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
  await db.routines.clear();
  await db.routineDays.clear();
  await db.routineExercises.clear();
});

describe('sesiones', () => {
  it('empieza un entreno libre vacío', async () => {
    const s = await startSession({});
    expect(s.id).toBeTruthy();
    expect(s.routineDayId).toBeUndefined();
    expect(s.fecha).toBeGreaterThan(0);
    expect(await listSessionExercises(s.id)).toHaveLength(0);
  });

  it('empieza desde un día de rutina y precarga sus ejercicios en orden', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const d = await addDay(r.id, { nombre: 'Empuje' });
    await addExerciseToDay(d.id, { exerciseId: 'seed-press-banca' });
    await addExerciseToDay(d.id, { exerciseId: 'seed-press-militar' });
    const s = await startSession({ routineDayId: d.id });
    const les = await listSessionExercises(s.id);
    expect(les.map((le) => le.exerciseId)).toEqual(['seed-press-banca', 'seed-press-militar']);
  });

  it('finaliza la sesión guardando duración y notas', async () => {
    const s = await startSession({});
    await new Promise((res) => setTimeout(res, 5));
    await finishSession(s.id, { notas: 'buen día' });
    const after = await getSession(s.id);
    expect(after?.notas).toBe('buen día');
    expect(after?.duracionSegundos).toBeGreaterThanOrEqual(0);
  });

  it('lista las sesiones no borradas de más reciente a más antigua', async () => {
    const a = await startSession({});
    await new Promise((res) => setTimeout(res, 3));
    const b = await startSession({});
    const ids = (await listSessions()).map((x) => x.id);
    expect(ids).toEqual([b.id, a.id]);
  });

  it('borra una sesión en cascada con sus ejercicios y series', async () => {
    const s = await startSession({});
    const le = await addLoggedExercise(s.id, 'seed-press-banca');
    await addSet(le.id, { peso: 60, reps: 8 });
    await softDeleteSession(s.id);
    expect(await listSessions()).toHaveLength(0);
    expect(await listSessionExercises(s.id)).toHaveLength(0);
    expect(await listExerciseSets(le.id)).toHaveLength(0);
    expect((await getSession(s.id))!.deletedAt).not.toBeNull();
  });
});

describe('ejercicios y series', () => {
  it('añade series con orden incremental y las edita/borra', async () => {
    const s = await startSession({});
    const le = await addLoggedExercise(s.id, 'seed-press-banca');
    const set1 = await addSet(le.id, { peso: 60, reps: 8 });
    const set2 = await addSet(le.id, { peso: 60, reps: 7 });
    expect(set1.orden).toBe(0);
    expect(set2.orden).toBe(1);
    await updateSet(set1.id, { reps: 9 });
    expect((await db.loggedSets.get(set1.id))?.reps).toBe(9);
    await softDeleteSet(set2.id);
    expect(await listExerciseSets(le.id)).toHaveLength(1);
  });

  it('borra un ejercicio registrado en cascada con sus series', async () => {
    const s = await startSession({});
    const le = await addLoggedExercise(s.id, 'seed-press-banca');
    await addSet(le.id, { peso: 60, reps: 8 });
    await softDeleteLoggedExercise(le.id);
    expect(await listSessionExercises(s.id)).toHaveLength(0);
    expect(await listExerciseSets(le.id)).toHaveLength(0);
  });
});

describe('autorrelleno', () => {
  it('getLastSet devuelve la última serie del mismo ejercicio en una sesión anterior', async () => {
    const vieja = await startSession({});
    const leVieja = await addLoggedExercise(vieja.id, 'seed-sentadilla');
    await addSet(leVieja.id, { peso: 80, reps: 5 });
    await addSet(leVieja.id, { peso: 85, reps: 5 });
    await new Promise((res) => setTimeout(res, 3));
    const nueva = await startSession({});
    const last = await getLastSet('seed-sentadilla', nueva.id);
    expect(last).toMatchObject({ peso: 85, reps: 5 });
  });

  it('getLastSet ignora la sesión excluida y devuelve undefined si no hay histórico', async () => {
    const s = await startSession({});
    const le = await addLoggedExercise(s.id, 'seed-sentadilla');
    await addSet(le.id, { peso: 100, reps: 3 });
    expect(await getLastSet('seed-sentadilla', s.id)).toBeUndefined();
  });
});
