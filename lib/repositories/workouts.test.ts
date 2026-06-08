import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { createRoutine, addExerciseToRoutine } from '@/lib/repositories/routines';
import {
  startSession, getSession, listSessions, finishSession, softDeleteSession,
  addLoggedExercise, listSessionExercises, softDeleteLoggedExercise,
  addSet, updateSet, softDeleteSet, listExerciseSets, getLastSet,
  countSessionsWithoutGym, assignGymToSessionsWithoutGym,
  getLastRoutineSession, getLastPerformance, getLastWorkingSets,
} from '@/lib/repositories/workouts';

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
  await db.routines.clear();
  await db.routineExercises.clear();
  await db.gyms.clear();
});

describe('sesiones', () => {
  it('empieza un entreno libre vacío', async () => {
    const s = await startSession({});
    expect(s.id).toBeTruthy();
    expect(s.routineDayId).toBeUndefined();
    expect(s.fecha).toBeGreaterThan(0);
    expect(await listSessionExercises(s.id)).toHaveLength(0);
  });

  it('empieza desde una rutina y precarga sus ejercicios en orden', async () => {
    const r = await createRoutine({ nombre: 'R' });
    await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
    await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-militar' });
    const s = await startSession({ routineId: r.id });
    const les = await listSessionExercises(s.id);
    expect(les.map((le) => le.exerciseId)).toEqual(['seed-press-banca', 'seed-press-militar']);
  });

  it('guarda routineId al empezar desde rutina y lo deja null en libre', async () => {
    const r = await createRoutine({ nombre: 'R' });
    const conRutina = await startSession({ routineId: r.id });
    const libre = await startSession({});
    expect(conRutina.routineId).toBe(r.id);
    expect(libre.routineId).toBeNull();
  });

  it('getLastRoutineSession ignora libres/borrados y da la más reciente con rutina', async () => {
    const r1 = await createRoutine({ nombre: 'R1' });
    const r2 = await createRoutine({ nombre: 'R2' });
    expect(await getLastRoutineSession()).toBeUndefined();
    await startSession({ routineId: r1.id });
    await new Promise((res) => setTimeout(res, 3));
    await startSession({}); // libre, no cuenta
    await new Promise((res) => setTimeout(res, 3));
    const ultima = await startSession({ routineId: r2.id });
    await new Promise((res) => setTimeout(res, 3));
    await startSession({}); // libre posterior, tampoco cuenta
    expect((await getLastRoutineSession())?.id).toBe(ultima.id);
    expect((await getLastRoutineSession())?.routineId).toBe(r2.id);
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

describe('gimnasios', () => {
  it('startSession guarda el gymId', async () => {
    const s = await startSession({ gymId: 'g1' });
    expect((await getSession(s.id))?.gymId).toBe('g1');
  });

  it('getLastSet filtra por gimnasio', async () => {
    const a = await startSession({ gymId: 'gymA' });
    const leA = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(leA.id, { peso: 100, reps: 5 });
    await new Promise((res) => setTimeout(res, 3));
    const b = await startSession({ gymId: 'gymB' });
    const leB = await addLoggedExercise(b.id, 'seed-sentadilla');
    await addSet(leB.id, { peso: 80, reps: 5 });
    await new Promise((res) => setTimeout(res, 3));
    const nueva = await startSession({ gymId: 'gymA' });
    // Sin filtro: la más reciente (gymB, 80).
    expect(await getLastSet('seed-sentadilla', nueva.id)).toMatchObject({ peso: 80 });
    // Filtrando por gymA: la de gymA (100).
    expect(await getLastSet('seed-sentadilla', nueva.id, 'gymA')).toMatchObject({ peso: 100 });
  });

  it('backfill asigna gymId a las sesiones sin gimnasio y cuenta', async () => {
    await startSession({}); // sin gym
    await startSession({}); // sin gym
    await startSession({ gymId: 'g1' });
    expect(await countSessionsWithoutGym()).toBe(2);
    const n = await assignGymToSessionsWithoutGym('g1');
    expect(n).toBe(2);
    expect(await countSessionsWithoutGym()).toBe(0);
    const todas = await listSessions();
    expect(todas.every((s) => s.gymId === 'g1')).toBe(true);
  });
});

describe('getLastPerformance', () => {
  it('devuelve peso, reps y fecha del último set del mismo ejercicio', async () => {
    const vieja = await startSession({});
    const le = await addLoggedExercise(vieja.id, 'seed-sentadilla');
    await addSet(le.id, { peso: 80, reps: 5 });
    await addSet(le.id, { peso: 85, reps: 6 });
    await new Promise((res) => setTimeout(res, 3));
    const nueva = await startSession({});
    const perf = await getLastPerformance('seed-sentadilla', nueva.id);
    expect(perf).toMatchObject({ peso: 85, reps: 6, fecha: vieja.fecha });
  });

  it('filtra por gimnasio igual que getLastSet', async () => {
    const a = await startSession({ gymId: 'gymA' });
    const leA = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(leA.id, { peso: 100, reps: 5 });
    await new Promise((res) => setTimeout(res, 3));
    const b = await startSession({ gymId: 'gymB' });
    const leB = await addLoggedExercise(b.id, 'seed-sentadilla');
    await addSet(leB.id, { peso: 80, reps: 5 });
    await new Promise((res) => setTimeout(res, 3));
    const nueva = await startSession({ gymId: 'gymA' });
    expect(await getLastPerformance('seed-sentadilla', nueva.id, 'gymA')).toMatchObject({ peso: 100 });
  });

  it('undefined si no hay histórico (excluyendo la sesión actual)', async () => {
    const s = await startSession({});
    const le = await addLoggedExercise(s.id, 'seed-sentadilla');
    await addSet(le.id, { peso: 100, reps: 3 });
    expect(await getLastPerformance('seed-sentadilla', s.id)).toBeUndefined();
  });
});

describe('getLastWorkingSets', () => {
  it('getLastWorkingSets devuelve las series de trabajo de la última sesión, sin calentamiento', async () => {
    const s1 = await startSession({ gymId: 'g1' });
    const le1 = await addLoggedExercise(s1.id, 'ex-1');
    await addSet(le1.id, { peso: 30, reps: 12, esCalentamiento: true });
    await addSet(le1.id, { peso: 40, reps: 12 });
    await addSet(le1.id, { peso: 40, reps: 11 });

    const res = await getLastWorkingSets('ex-1', undefined, 'g1');
    expect(res).toEqual([
      { peso: 40, reps: 12 },
      { peso: 40, reps: 11 },
    ]);
  });

  it('getLastWorkingSets filtra por gimnasio', async () => {
    const sA = await startSession({ gymId: 'gA' });
    const leA = await addLoggedExercise(sA.id, 'ex-2');
    await addSet(leA.id, { peso: 50, reps: 10 });
    const res = await getLastWorkingSets('ex-2', undefined, 'gB');
    expect(res).toBeUndefined();
  });

  it('getLastWorkingSets devuelve undefined si solo hay calentamiento', async () => {
    const s = await startSession({ gymId: 'gW' });
    const le = await addLoggedExercise(s.id, 'ex-warm');
    await addSet(le.id, { peso: 20, reps: 15, esCalentamiento: true });
    expect(await getLastWorkingSets('ex-warm', undefined, 'gW')).toBeUndefined();
  });
});
