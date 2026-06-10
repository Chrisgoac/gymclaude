import { db } from '@/lib/db/database';
import { MUSCLE_GROUPS } from '@/lib/db/types';
import type { LoggedSet, MuscleGroup, WorkoutSession } from '@/lib/db/types';
import { detectarEstancamiento } from '@/lib/insights';
import { calcularRacha } from '@/lib/logros';

const DIA_MS = 86400000;

const activo = <T extends { deletedAt: number | null }>(arr: T[]) => arr.filter((x) => x.deletedAt === null);

export function estimar1RM(peso: number, reps: number): number {
  if (reps <= 1) return peso;
  return Math.round(peso * (1 + reps / 30) * 10) / 10;
}

async function setsDeEjercicio(
  exerciseId: string,
  gymId?: string | null,
  excludeSessionId?: string,
): Promise<{ set: LoggedSet; fecha: number }[]> {
  const les = activo(await db.loggedExercises.where('exerciseId').equals(exerciseId).toArray())
    .filter((le) => le.sessionId !== excludeSessionId);
  if (les.length === 0) return [];
  const sessionIds = [...new Set(les.map((le) => le.sessionId))];
  const sessions = await db.workoutSessions.bulkGet(sessionIds);
  const fechaBy = new Map<string, number>();
  for (const s of sessions) {
    if (!s || s.deletedAt !== null) continue;
    if (gymId != null && (s.gymId ?? null) !== gymId) continue; // filtro por gimnasio
    fechaBy.set(s.id, s.fecha);
  }
  const out: { set: LoggedSet; fecha: number }[] = [];
  for (const le of les) {
    const fecha = fechaBy.get(le.sessionId);
    if (fecha === undefined) continue;
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    for (const set of sets) out.push({ set, fecha });
  }
  return out;
}

export interface ExerciseProgressPoint {
  fecha: number;
  maxPeso: number;
  mejor1RM: number;
  volumen: number;
}

export async function getExerciseProgress(
  exerciseId: string,
  gymId?: string | null,
  sinceTs = 0,
  excludeSessionId?: string,
): Promise<ExerciseProgressPoint[]> {
  const data = await setsDeEjercicio(exerciseId, gymId, excludeSessionId);
  const byFecha = new Map<number, LoggedSet[]>();
  for (const { set, fecha } of data) {
    const arr = byFecha.get(fecha) ?? [];
    arr.push(set);
    byFecha.set(fecha, arr);
  }
  const points: ExerciseProgressPoint[] = [];
  for (const [fecha, sets] of byFecha) {
    points.push({
      fecha,
      maxPeso: Math.max(...sets.map((s) => s.peso)),
      mejor1RM: Math.max(...sets.map((s) => estimar1RM(s.peso, s.reps))),
      volumen: sets.reduce((acc, s) => acc + s.peso * s.reps, 0),
    });
  }
  return points.filter((p) => p.fecha >= sinceTs).sort((a, b) => a.fecha - b.fecha);
}

export interface ExercisePRs {
  maxPeso: number;
  mejor1RM: number;
}

export async function getExercisePRs(exerciseId: string, gymId?: string | null): Promise<ExercisePRs | null> {
  const data = await setsDeEjercicio(exerciseId, gymId);
  if (data.length === 0) return null;
  let maxPeso = 0;
  let mejor1RM = 0;
  for (const { set } of data) {
    maxPeso = Math.max(maxPeso, set.peso);
    mejor1RM = Math.max(mejor1RM, estimar1RM(set.peso, set.reps));
  }
  return { maxPeso, mejor1RM };
}

export interface VolumeByMuscle {
  grupo: MuscleGroup;
  volumen: number;
}

/** Volumen (kg·rep) por grupo muscular de la semana ISO actual; 0 para grupos sin volumen. `now` inyectable. */
export async function getVolumenSemanaByMuscle(gymId?: string | null, now: number = Date.now()): Promise<Record<MuscleGroup, number>> {
  const arr = await getVolumeByMuscle(inicioSemana(now), gymId);
  const result = Object.fromEntries(MUSCLE_GROUPS.map((g) => [g, 0])) as Record<MuscleGroup, number>;
  for (const v of arr) result[v.grupo] = v.volumen;
  return result;
}

export async function getVolumeByMuscle(sinceTs = 0, gymId?: string | null): Promise<VolumeByMuscle[]> {
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => s.fecha >= sinceTs)
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId);
  const sessionIds = new Set(sessions.map((s) => s.id));
  if (sessionIds.size === 0) return [];
  const les = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const exerciseIds = [...new Set(les.map((le) => le.exerciseId))];
  const exercises = await db.exercises.bulkGet(exerciseIds);
  const grupoBy = new Map<string, MuscleGroup>();
  for (const e of exercises) if (e) grupoBy.set(e.id, e.grupoMuscular);
  const volByGrupo = new Map<MuscleGroup, number>();
  for (const le of les) {
    const grupo = grupoBy.get(le.exerciseId);
    if (!grupo) continue;
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    const vol = sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
    volByGrupo.set(grupo, (volByGrupo.get(grupo) ?? 0) + vol);
  }
  return [...volByGrupo.entries()]
    .map(([grupo, volumen]) => ({ grupo, volumen }))
    .sort((a, b) => b.volumen - a.volumen);
}

export interface SessionSummary {
  session: WorkoutSession;
  numEjercicios: number;
  volumen: number;
}

export async function listSessionSummaries(gymId?: string | null): Promise<SessionSummary[]> {
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId)
    .sort((a, b) => b.fecha - a.fecha);
  const out: SessionSummary[] = [];
  for (const session of sessions) {
    const les = activo(await db.loggedExercises.where('sessionId').equals(session.id).toArray());
    let volumen = 0;
    for (const le of les) {
      const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
      volumen += sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
    }
    out.push({ session, numEjercicios: les.length, volumen });
  }
  return out;
}

export interface PeriodSummary {
  sesiones: number;
  volumen: number;
}

export async function getPeriodSummary(sinceTs = 0, gymId?: string | null): Promise<PeriodSummary> {
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => s.fecha >= sinceTs)
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId);
  if (sessions.length === 0) return { sesiones: 0, volumen: 0 };
  const sessionIds = new Set(sessions.map((s) => s.id));
  const les = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  let volumen = 0;
  for (const le of les) {
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    volumen += sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
  }
  return { sesiones: sessions.length, volumen };
}

export interface WeeklyVolumePoint {
  semanaInicioTs: number;
  volumen: number;
}

/** Lunes 00:00 (hora local) de la semana que contiene ts. */
function inicioSemana(ts: number): number {
  const d = new Date(ts);
  d.setHours(0, 0, 0, 0);
  const dow = (d.getDay() + 6) % 7; // 0 = lunes
  d.setDate(d.getDate() - dow);
  return d.getTime();
}

export interface WeeklySummary {
  sesiones: number;
  volumenSemana: number;
  volumenSemanaPrevia: number;
  deltaPct: number | null;
}

/** Resumen de la semana ISO actual (lunes) vs la previa. `now` inyectable para tests. */
export async function getWeeklySummary(gymId?: string | null, now: number = Date.now()): Promise<WeeklySummary> {
  const inicioActual = inicioSemana(now);
  // inicioActual - 1 ms cae en el domingo de la semana previa → inicioSemana lo lleva a su lunes.
  const inicioPrevia = inicioSemana(inicioActual - 1);
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId)
    .filter((s) => s.fecha >= inicioPrevia);
  const sessionIds = new Set(sessions.map((s) => s.id));
  const les = sessionIds.size === 0
    ? []
    : activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const volBySession = new Map<string, number>();
  for (const le of les) {
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    const vol = sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
    volBySession.set(le.sessionId, (volBySession.get(le.sessionId) ?? 0) + vol);
  }
  let sesiones = 0, volumenSemana = 0, volumenSemanaPrevia = 0;
  for (const s of sessions) {
    const vol = volBySession.get(s.id) ?? 0;
    if (s.fecha >= inicioActual) { sesiones++; volumenSemana += vol; }
    else { volumenSemanaPrevia += vol; }
  }
  const deltaPct = volumenSemanaPrevia > 0
    ? Math.round(((volumenSemana - volumenSemanaPrevia) / volumenSemanaPrevia) * 100)
    : null;
  return { sesiones, volumenSemana, volumenSemanaPrevia, deltaPct };
}

export async function getWeeklyVolume(sinceTs = 0, gymId?: string | null): Promise<WeeklyVolumePoint[]> {
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => s.fecha >= sinceTs)
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId);
  if (sessions.length === 0) return [];
  const sessionIds = new Set(sessions.map((s) => s.id));
  const les = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const volBySession = new Map<string, number>();
  for (const le of les) {
    const sets = activo(await db.loggedSets.where('loggedExerciseId').equals(le.id).toArray());
    const vol = sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
    volBySession.set(le.sessionId, (volBySession.get(le.sessionId) ?? 0) + vol);
  }
  const byWeek = new Map<number, number>();
  for (const s of sessions) {
    const semana = inicioSemana(s.fecha);
    byWeek.set(semana, (byWeek.get(semana) ?? 0) + (volBySession.get(s.id) ?? 0));
  }
  return [...byWeek.entries()]
    .map(([semanaInicioTs, volumen]) => ({ semanaInicioTs, volumen }))
    .sort((a, b) => a.semanaInicioTs - b.semanaInicioTs);
}

const dayKey = (ts: number) => {
  const d = new Date(ts);
  return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
};

export async function getCurrentStreakDays(): Promise<number> {
  const sessions = activo(await db.workoutSessions.toArray());
  if (sessions.length === 0) return 0;
  const days = new Set(sessions.map((s) => dayKey(s.fecha)));
  let streak = 0;
  const cursor = new Date();
  while (days.has(dayKey(cursor.getTime()))) {
    streak++;
    cursor.setDate(cursor.getDate() - 1);
  }
  return streak;
}

export interface Estancado {
  exerciseId: string;
  nombre: string;
  sesionesSinMejora: number;
  ultimaMejoraFecha: number | null;
}

/** Un PR batido esta semana. */
export interface PRSemana {
  exerciseId: string;
  nombre: string;
  tipo: 'peso' | '1rm';
}

/** Ejercicios que batieron su récord (peso o 1RM estimado) esta semana respecto a su histórico previo. */
export async function getPRsThisWeek(gymId?: string | null, now: number = Date.now()): Promise<PRSemana[]> {
  const inicio = inicioSemana(now);
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId)
    .filter((s) => s.fecha >= inicio);
  const sessionIds = new Set(sessions.map((s) => s.id));
  if (sessionIds.size === 0) return [];
  const lesWeek = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const exerciseIds = [...new Set(lesWeek.map((le) => le.exerciseId))];
  const exercises = await db.exercises.bulkGet(exerciseIds);
  const nombreBy = new Map<string, string>();
  for (const e of exercises) if (e) nombreBy.set(e.id, e.nombre);
  const out: PRSemana[] = [];
  for (const exerciseId of exerciseIds) {
    const data = await setsDeEjercicio(exerciseId, gymId);
    const week = data.filter((d) => d.fecha >= inicio);
    const before = data.filter((d) => d.fecha < inicio);
    if (week.length === 0 || before.length === 0) continue; // sin histórico previo → no es "batir"
    const maxPesoWeek = Math.max(...week.map((d) => d.set.peso));
    const maxPesoBefore = Math.max(...before.map((d) => d.set.peso));
    if (maxPesoWeek > maxPesoBefore) {
      out.push({ exerciseId, nombre: nombreBy.get(exerciseId) ?? '—', tipo: 'peso' });
      continue;
    }
    const max1rmWeek = Math.max(...week.map((d) => estimar1RM(d.set.peso, d.set.reps)));
    const max1rmBefore = Math.max(...before.map((d) => estimar1RM(d.set.peso, d.set.reps)));
    if (max1rmWeek > max1rmBefore) {
      out.push({ exerciseId, nombre: nombreBy.get(exerciseId) ?? '—', tipo: '1rm' });
    }
  }
  return out;
}

/** Timestamp (epoch ms) de la última sesión que entrenó cada grupo muscular; null si nunca (en ese gym). */
export async function getLastTrainedByMuscle(gymId?: string | null): Promise<Record<MuscleGroup, number | null>> {
  const result = Object.fromEntries(MUSCLE_GROUPS.map((g) => [g, null])) as Record<MuscleGroup, number | null>;
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId);
  const fechaBy = new Map(sessions.map((s) => [s.id, s.fecha]));
  const sessionIds = new Set(sessions.map((s) => s.id));
  if (sessionIds.size === 0) return result;
  const les = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const exerciseIds = [...new Set(les.map((le) => le.exerciseId))];
  const exercises = await db.exercises.bulkGet(exerciseIds);
  const grupoBy = new Map<string, MuscleGroup>();
  for (const e of exercises) if (e) grupoBy.set(e.id, e.grupoMuscular);
  for (const le of les) {
    const grupo = grupoBy.get(le.exerciseId);
    if (!grupo) continue;
    const fecha = fechaBy.get(le.sessionId) ?? 0;
    if (result[grupo] == null || fecha > (result[grupo] as number)) result[grupo] = fecha;
  }
  return result;
}

export interface WeeklyVolumeDelta extends WeeklyVolumePoint {
  deltaPct: number | null;
}

/** Anota cada punto de volumen semanal con el % de cambio vs la semana anterior (primera = null). */
export function weeklyVolumeDeltas(points: WeeklyVolumePoint[]): WeeklyVolumeDelta[] {
  return points.map((p, i) => {
    if (i === 0) return { ...p, deltaPct: null };
    const prev = points[i - 1].volumen;
    const deltaPct = prev > 0 ? Math.round(((p.volumen - prev) / prev) * 100) : null;
    return { ...p, deltaPct };
  });
}

/** Ejercicios entrenados (en ese gym) cuyo mejor 1RM estimado está estancado. */
export async function listEstancados(gymId?: string | null): Promise<Estancado[]> {
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId);
  const sessionIds = new Set(sessions.map((s) => s.id));
  if (sessionIds.size === 0) return [];
  const les = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const exerciseIds = [...new Set(les.map((le) => le.exerciseId))];
  const exercises = await db.exercises.bulkGet(exerciseIds);
  const nombreBy = new Map<string, string>();
  for (const e of exercises) if (e) nombreBy.set(e.id, e.nombre);
  const out: Estancado[] = [];
  for (const exerciseId of exerciseIds) {
    const points = await getExerciseProgress(exerciseId, gymId);
    const e = detectarEstancamiento(points);
    if (!e.estancado) continue;
    out.push({
      exerciseId,
      nombre: nombreBy.get(exerciseId) ?? '—',
      sesionesSinMejora: e.sesionesSinMejora,
      ultimaMejoraFecha: e.ultimaMejoraFecha,
    });
  }
  return out.sort((a, b) => b.sesionesSinMejora - a.sesionesSinMejora);
}

/** Racha (actual + mejor) de semanas consecutivas cumpliendo el objetivo de sesiones. */
export async function getRachaSemanal(objetivo: number, now: number = Date.now()): Promise<{ actual: number; mejor: number }> {
  const sessions = activo(await db.workoutSessions.toArray());
  const byWeek = new Map<number, number>();
  for (const s of sessions) {
    const w = inicioSemana(s.fecha);
    byWeek.set(w, (byWeek.get(w) ?? 0) + 1);
  }
  const semanas = [...byWeek.entries()].map(([inicioTs, sesiones]) => ({ inicioTs, sesiones }));
  return calcularRacha(semanas, objetivo, inicioSemana(now));
}

export interface PRItem {
  exerciseId: string;
  nombre: string;
  peso: number;
  fecha: number;
}

/** Mejor peso por ejercicio entrenado, con la fecha más antigua en que se alcanzó. */
export async function listPRs(): Promise<PRItem[]> {
  const sessions = activo(await db.workoutSessions.toArray());
  const fechaBySession = new Map(sessions.map((s) => [s.id, s.fecha]));
  const les = activo(await db.loggedExercises.toArray());
  const leInfo = new Map(les.map((le) => [le.id, { exerciseId: le.exerciseId, fecha: fechaBySession.get(le.sessionId) ?? 0 }]));
  const sets = activo(await db.loggedSets.toArray());

  // 1ª pasada: máx peso por ejercicio. 2ª: fecha más antigua que alcanza ese máx.
  const maxPeso = new Map<string, number>();
  for (const set of sets) {
    const info = leInfo.get(set.loggedExerciseId);
    if (!info) continue;
    maxPeso.set(info.exerciseId, Math.max(maxPeso.get(info.exerciseId) ?? 0, set.peso));
  }
  const fechaPR = new Map<string, number>();
  for (const set of sets) {
    const info = leInfo.get(set.loggedExerciseId);
    if (!info) continue;
    if (set.peso === maxPeso.get(info.exerciseId)) {
      const prev = fechaPR.get(info.exerciseId);
      if (prev === undefined || info.fecha < prev) fechaPR.set(info.exerciseId, info.fecha);
    }
  }
  const ids = [...maxPeso.keys()];
  const exs = await db.exercises.bulkGet(ids);
  return ids
    .map((id, i) => ({ exerciseId: id, nombre: exs[i]?.nombre ?? '—', peso: maxPeso.get(id)!, fecha: fechaPR.get(id) ?? 0 }))
    .sort((a, b) => b.peso - a.peso);
}

/** Métricas agregadas para evaluar logros. `objetivo` = objetivo semanal (para la mejor racha). */
export async function getLogroMetricas(objetivo: number, now: number = Date.now()): Promise<import('@/lib/logros').LogroMetricas> {
  const sessions = activo(await db.workoutSessions.toArray());
  const sets = activo(await db.loggedSets.toArray());
  const les = activo(await db.loggedExercises.toArray());
  const exById = new Map(les.map((le) => [le.id, le.exerciseId]));

  const volumenTotal = sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
  const exConSets = new Set<string>();
  for (const s of sets) {
    const ex = exById.get(s.loggedExerciseId);
    if (ex) exConSets.add(ex);
  }
  const mesos = activo(await db.mesocycles.toArray());
  const mesociclosCompletados = mesos.filter((m) => m.fechaInicio + m.semanas * 7 * DIA_MS < now).length;
  const { mejor } = await getRachaSemanal(objetivo, now);

  return {
    sesionesTotales: sessions.length,
    volumenTotal,
    prsTotales: exConSets.size,
    mejorRacha: mejor,
    mesociclosCompletados,
  };
}
