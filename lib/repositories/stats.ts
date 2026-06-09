import { db } from '@/lib/db/database';
import type { LoggedSet, MuscleGroup, WorkoutSession } from '@/lib/db/types';
import { detectarEstancamiento } from '@/lib/insights';

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
