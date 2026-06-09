import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { startSession, addLoggedExercise, addSet } from '@/lib/repositories/workouts';
import {
  estimar1RM, getExerciseProgress, getExercisePRs, getVolumeByMuscle,
  listSessionSummaries, getCurrentStreakDays, getPeriodSummary, getWeeklyVolume,
  listEstancados, getWeeklySummary, getPRsThisWeek,
} from '@/lib/repositories/stats';

const DAY = 24 * 60 * 60 * 1000;
// 2026-06-10 es miércoles. Semana actual: lun 8 … dom 14. Semana previa: lun 1 … dom 7.
const NOW_WED = new Date('2026-06-10T12:00:00').getTime();

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

  it('filtra los puntos por sinceTs', async () => {
    await sesionCon(2 * DAY, 'seed-press-banca', [[60, 8]]);
    await sesionCon(5 * DAY, 'seed-press-banca', [[65, 8]]);
    const prog = await getExerciseProgress('seed-press-banca', undefined, 3 * DAY);
    expect(prog.map((p) => p.fecha)).toEqual([5 * DAY]);
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

describe('getPeriodSummary', () => {
  it('cuenta sesiones y suma volumen del periodo', async () => {
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 10]]); // 600
    await sesionCon(3 * DAY, 'seed-sentadilla', [[100, 5]]);  // 500
    const r = await getPeriodSummary(0);
    expect(r.sesiones).toBe(2);
    expect(r.volumen).toBe(1100);
  });
  it('respeta sinceTs', async () => {
    await sesionCon(1 * DAY, 'seed-press-banca', [[60, 10]]);
    await sesionCon(5 * DAY, 'seed-sentadilla', [[100, 5]]);
    const r = await getPeriodSummary(3 * DAY);
    expect(r.sesiones).toBe(1);
    expect(r.volumen).toBe(500);
  });
  it('respeta el filtro de gimnasio', async () => {
    const a = await startSession({ gymId: 'gymA' });
    const le = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(le.id, { peso: 100, reps: 5 });
    await startSession({ gymId: 'gymB' });
    const r = await getPeriodSummary(0, 'gymA');
    expect(r.sesiones).toBe(1);
    expect(r.volumen).toBe(500);
  });
});

describe('getWeeklyVolume', () => {
  it('agrupa el volumen por semana (lunes) y ordena ascendente', async () => {
    // 2021-01-04 = lunes. Dos sesiones esa semana + una la semana siguiente.
    const lunes = new Date(2021, 0, 4).getTime();
    const miercoles = new Date(2021, 0, 6).getTime();
    const lunesSig = new Date(2021, 0, 11).getTime();
    await sesionCon(lunes, 'seed-press-banca', [[60, 10]]);      // 600
    await sesionCon(miercoles, 'seed-sentadilla', [[100, 5]]);   // 500
    await sesionCon(lunesSig, 'seed-press-banca', [[70, 10]]);   // 700

    const semanas = await getWeeklyVolume(0);
    expect(semanas).toHaveLength(2);
    expect(semanas[0].semanaInicioTs).toBe(new Date(2021, 0, 4).getTime());
    expect(semanas[0].volumen).toBe(1100);
    expect(semanas[1].volumen).toBe(700);
  });
  it('respeta sinceTs y el gimnasio', async () => {
    const a = await startSession({ gymId: 'gymA' });
    await db.workoutSessions.update(a.id, { fecha: new Date(2021, 0, 4).getTime() });
    const le = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(le.id, { peso: 100, reps: 5 });
    expect(await getWeeklyVolume(0, 'gymB')).toHaveLength(0);
  });
});

describe('listEstancados', () => {
  it('listEstancados detecta un ejercicio con 1RM plano (4 sesiones) y respeta el gym', async () => {
    await db.exercises.put({
      id: 'ex-est', userId: null, nombre: 'Press estancado', grupoMuscular: 'pecho',
      equipamiento: 'maquina', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null,
    });
    for (let i = 0; i < 4; i++) {
      const s = { id: `s${i}`, userId: null, gymId: 'g1', fecha: 1000 + i * 86400000, updatedAt: 1, deletedAt: null };
      await db.workoutSessions.put(s);
      const le = { id: `le${i}`, sessionId: s.id, exerciseId: 'ex-est', orden: 0, updatedAt: 1, deletedAt: null };
      await db.loggedExercises.put(le);
      await db.loggedSets.put({ id: `set${i}`, loggedExerciseId: le.id, orden: 0, peso: 40, reps: 10, updatedAt: 1, deletedAt: null });
    }
    const enG1 = await listEstancados('g1');
    expect(enG1.map((e) => e.exerciseId)).toContain('ex-est');
    const enG2 = await listEstancados('g2');
    expect(enG2.map((e) => e.exerciseId)).not.toContain('ex-est');
  });

  it('listEstancados NO incluye un ejercicio que sigue mejorando', async () => {
    await db.exercises.put({
      id: 'ex-prog', userId: null, nombre: 'Press progresando', grupoMuscular: 'pecho',
      equipamiento: 'maquina', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null,
    });
    const pesos = [40, 42.5, 45, 47.5]; // sube cada sesión → nuevo 1RM máx en la última
    for (let i = 0; i < 4; i++) {
      const s = { id: `sp${i}`, userId: null, gymId: 'gP', fecha: 2000 + i * 86400000, updatedAt: 1, deletedAt: null };
      await db.workoutSessions.put(s);
      const le = { id: `lep${i}`, sessionId: s.id, exerciseId: 'ex-prog', orden: 0, updatedAt: 1, deletedAt: null };
      await db.loggedExercises.put(le);
      await db.loggedSets.put({ id: `setp${i}`, loggedExerciseId: le.id, orden: 0, peso: pesos[i], reps: 10, updatedAt: 1, deletedAt: null });
    }
    const res = await listEstancados('gP');
    expect(res.map((e) => e.exerciseId)).not.toContain('ex-prog');
  });
});

describe('filtro por gimnasio', () => {
  it('getExercisePRs y getExerciseProgress filtran por gimnasio', async () => {
    const a = await startSession({ gymId: 'gymA' });
    const leA = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(leA.id, { peso: 100, reps: 5 });
    const b = await startSession({ gymId: 'gymB' });
    const leB = await addLoggedExercise(b.id, 'seed-sentadilla');
    await addSet(leB.id, { peso: 60, reps: 5 });

    expect((await getExercisePRs('seed-sentadilla'))?.maxPeso).toBe(100);
    expect((await getExercisePRs('seed-sentadilla', 'gymB'))?.maxPeso).toBe(60);
    const progB = await getExerciseProgress('seed-sentadilla', 'gymB');
    expect(progB).toHaveLength(1);
    expect(progB[0].maxPeso).toBe(60);
  });

  it('listSessionSummaries y getVolumeByMuscle filtran por gimnasio', async () => {
    const a = await startSession({ gymId: 'gymA' });
    const leA = await addLoggedExercise(a.id, 'seed-sentadilla');
    await addSet(leA.id, { peso: 100, reps: 5 });
    await startSession({ gymId: 'gymB' });

    expect(await listSessionSummaries()).toHaveLength(2);
    expect(await listSessionSummaries('gymA')).toHaveLength(1);
    const volA = await getVolumeByMuscle(0, 'gymA');
    expect(volA.reduce((acc, v) => acc + v.volumen, 0)).toBe(500);
    expect(await getVolumeByMuscle(0, 'gymB')).toHaveLength(0);
  });

  it('getExerciseProgress excluye la sesión indicada (excludeSessionId)', async () => {
    await db.exercises.put({
      id: 'ex-exc', userId: null, nombre: 'X', grupoMuscular: 'pecho',
      equipamiento: 'maquina', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null,
    });
    const sA = { id: 'exc-a', userId: null, gymId: 'g1', fecha: 1000, updatedAt: 1, deletedAt: null };
    const sB = { id: 'exc-b', userId: null, gymId: 'g1', fecha: 2000, updatedAt: 1, deletedAt: null };
    await db.workoutSessions.bulkPut([sA, sB]);
    const leA = { id: 'exc-lea', sessionId: 'exc-a', exerciseId: 'ex-exc', orden: 0, updatedAt: 1, deletedAt: null };
    const leB = { id: 'exc-leb', sessionId: 'exc-b', exerciseId: 'ex-exc', orden: 0, updatedAt: 1, deletedAt: null };
    await db.loggedExercises.bulkPut([leA, leB]);
    await db.loggedSets.put({ id: 'exc-sa', loggedExerciseId: 'exc-lea', orden: 0, peso: 40, reps: 10, updatedAt: 1, deletedAt: null });
    await db.loggedSets.put({ id: 'exc-sb', loggedExerciseId: 'exc-leb', orden: 0, peso: 50, reps: 10, updatedAt: 1, deletedAt: null });

    const all = await getExerciseProgress('ex-exc', 'g1');
    expect(all).toHaveLength(2);
    const sinB = await getExerciseProgress('ex-exc', 'g1', 0, 'exc-b');
    expect(sinB).toHaveLength(1);
    expect(sinB[0].fecha).toBe(1000); // solo la sesión A
  });
});

it('getWeeklySummary cuenta sesiones de la semana y compara volumen vs previa', async () => {
  const seed = async (id: string, fecha: number, peso: number) => {
    await db.workoutSessions.put({ id, userId: null, gymId: 'g1', fecha, updatedAt: 1, deletedAt: null });
    const le = { id: `${id}-le`, sessionId: id, exerciseId: 'ws-ex', orden: 0, updatedAt: 1, deletedAt: null };
    await db.loggedExercises.put(le);
    await db.loggedSets.put({ id: `${id}-set`, loggedExerciseId: le.id, orden: 0, peso, reps: 10, updatedAt: 1, deletedAt: null });
  };
  await seed('ws-a', NOW_WED, 50);
  await seed('ws-b', NOW_WED - 2 * DAY, 40);
  await seed('ws-c', NOW_WED - 7 * DAY, 30);

  const r = await getWeeklySummary('g1', NOW_WED);
  expect(r.sesiones).toBe(2);
  expect(r.volumenSemana).toBe(900);
  expect(r.volumenSemanaPrevia).toBe(300);
  expect(r.deltaPct).toBe(200);
});

it('getWeeklySummary deltaPct null sin semana previa', async () => {
  await db.workoutSessions.put({ id: 'wx-a', userId: null, gymId: 'g9', fecha: NOW_WED, updatedAt: 1, deletedAt: null });
  const le = { id: 'wx-le', sessionId: 'wx-a', exerciseId: 'wx-ex', orden: 0, updatedAt: 1, deletedAt: null };
  await db.loggedExercises.put(le);
  await db.loggedSets.put({ id: 'wx-set', loggedExerciseId: 'wx-le', orden: 0, peso: 20, reps: 5, updatedAt: 1, deletedAt: null });
  const r = await getWeeklySummary('g9', NOW_WED);
  expect(r.sesiones).toBe(1);
  expect(r.volumenSemana).toBe(100);
  expect(r.volumenSemanaPrevia).toBe(0);
  expect(r.deltaPct).toBeNull();
});

it('getPRsThisWeek detecta PR de peso esta semana y excluye sin-mejora / sin-histórico', async () => {
  const seedSet = async (sid: string, exerciseId: string, fecha: number, peso: number) => {
    await db.workoutSessions.put({ id: sid, userId: null, gymId: 'gpr', fecha, updatedAt: 1, deletedAt: null });
    const le = { id: `${sid}-le`, sessionId: sid, exerciseId, orden: 0, updatedAt: 1, deletedAt: null };
    await db.loggedExercises.put(le);
    await db.loggedSets.put({ id: `${sid}-set`, loggedExerciseId: le.id, orden: 0, peso, reps: 5, updatedAt: 1, deletedAt: null });
  };
  await db.exercises.put({ id: 'pr-sube', userId: null, nombre: 'Sube', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });
  await db.exercises.put({ id: 'pr-baja', userId: null, nombre: 'Baja', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });
  await db.exercises.put({ id: 'pr-nuevo', userId: null, nombre: 'Nuevo', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });

  await seedSet('pr-s1', 'pr-sube', NOW_WED - 7 * DAY, 60);
  await seedSet('pr-s2', 'pr-sube', NOW_WED, 65);
  await seedSet('pr-b1', 'pr-baja', NOW_WED - 7 * DAY, 80);
  await seedSet('pr-b2', 'pr-baja', NOW_WED, 70);
  await seedSet('pr-n1', 'pr-nuevo', NOW_WED, 50);

  const prs = await getPRsThisWeek('gpr', NOW_WED);
  const ids = prs.map((p) => p.exerciseId);
  expect(ids).toContain('pr-sube');
  expect(ids).not.toContain('pr-baja');
  expect(ids).not.toContain('pr-nuevo');
  expect(prs.find((p) => p.exerciseId === 'pr-sube')?.tipo).toBe('peso');
});
