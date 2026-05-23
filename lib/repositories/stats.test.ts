import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { startSession, addLoggedExercise, addSet } from '@/lib/repositories/workouts';
import {
  estimar1RM, getExerciseProgress, getExercisePRs, getVolumeByMuscle,
  listSessionSummaries, getCurrentStreakDays,
} from '@/lib/repositories/stats';

const DAY = 24 * 60 * 60 * 1000;

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
  await db.exercises.clear();
  await db.exercises.bulkPut([
    { id: 'seed-press-banca', userId: null, nombre: 'Press de banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 0, deletedAt: null },
    { id: 'seed-sentadilla', userId: null, nombre: 'Sentadilla', grupoMuscular: 'cuadriceps', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 0, deletedAt: null },
  ]);
});

async function sesionCon(fecha: number, exerciseId: string, series: [number, number][]) {
  const s = await startSession({});
  await db.workoutSessions.update(s.id, { fecha });
  const le = await addLoggedExercise(s.id, exerciseId);
  for (const [peso, reps] of series) await addSet(le.id, { peso, reps });
  return s;
}

describe('estimar1RM (Epley)', () => {
  it('devuelve el peso para 1 rep y aplica Epley para más', () => {
    expect(estimar1RM(100, 1)).toBe(100);
    expect(estimar1RM(100, 10)).toBeCloseTo(133.3, 1);
  });
});

describe('getExerciseProgress', () => {
  it('agrega por sesión y ordena por fecha ascendente', async () => {
    await sesionCon(2 * DAY, 'seed-press-banca', [[60, 8], [62.5, 6]]);
    await sesionCon(5 * DAY, 'seed-press-banca', [[65, 8]]);
    const prog = await getExerciseProgress('seed-press-banca');
    expect(prog.map((p) => p.fecha)).toEqual([2 * DAY, 5 * DAY]);
    expect(prog[0].maxPeso).toBe(62.5);
    expect(prog[0].volumen).toBe(60 * 8 + 62.5 * 6);
    expect(prog[1].maxPeso).toBe(65);
  });
});

describe('getExercisePRs', () => {
  it('devuelve null si no hay datos y el máximo peso/1RM si los hay', async () => {
    expect(await getExercisePRs('seed-press-banca')).toBeNull();
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 5], [80, 1]]);
    const pr = await getExercisePRs('seed-press-banca');
    expect(pr).not.toBeNull();
    expect(pr!.maxPeso).toBe(80);
    expect(pr!.mejor1RM).toBeGreaterThanOrEqual(80);
  });
});

describe('getVolumeByMuscle', () => {
  it('suma el volumen por grupo muscular', async () => {
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 10]]);
    await sesionCon(1 * DAY, 'seed-sentadilla', [[100, 5]]);
    const vol = await getVolumeByMuscle();
    const pecho = vol.find((v) => v.grupo === 'pecho');
    const cuads = vol.find((v) => v.grupo === 'cuadriceps');
    expect(pecho?.volumen).toBe(600);
    expect(cuads?.volumen).toBe(500);
  });

  it('respeta el filtro sinceTs', async () => {
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 10]]);
    const vol = await getVolumeByMuscle(2 * DAY);
    expect(vol).toHaveLength(0);
  });
});

describe('listSessionSummaries', () => {
  it('resume cada sesión (nº ejercicios y volumen), más reciente primero', async () => {
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 10]]);
    await sesionCon(3 * DAY, 'seed-sentadilla', [[100, 5]]);
    const res = await listSessionSummaries();
    expect(res).toHaveLength(2);
    expect(res[0].session.fecha).toBe(3 * DAY);
    expect(res[0].numEjercicios).toBe(1);
    expect(res[0].volumen).toBe(500);
  });
});

describe('getCurrentStreakDays', () => {
  it('es 0 sin sesiones', async () => {
    expect(await getCurrentStreakDays()).toBe(0);
  });
  it('cuenta 1 con una sesión hoy y no duplica dos el mismo día', async () => {
    await sesionCon(Date.now(), 'seed-press-banca', [[60, 5]]);
    await sesionCon(Date.now(), 'seed-sentadilla', [[100, 5]]);
    expect(await getCurrentStreakDays()).toBe(1);
  });
  it('es 0 si la única sesión es de hace días (racha rota)', async () => {
    await sesionCon(Date.now() - 10 * DAY, 'seed-press-banca', [[60, 5]]);
    expect(await getCurrentStreakDays()).toBe(0);
  });
});
