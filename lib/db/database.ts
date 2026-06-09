import Dexie, { type Table } from 'dexie';
import type {
  Exercise, Routine, RoutineExercise,
  WorkoutSession, LoggedExercise, LoggedSet, SyncState, Gym, ExercisePhoto, UserSetting, CoachMessage, BodyMetric, ProgressPhoto,
} from './types';

export class GymLogDB extends Dexie {
  exercises!: Table<Exercise, string>;
  routines!: Table<Routine, string>;
  routineExercises!: Table<RoutineExercise, string>;
  workoutSessions!: Table<WorkoutSession, string>;
  loggedExercises!: Table<LoggedExercise, string>;
  loggedSets!: Table<LoggedSet, string>;
  syncState!: Table<SyncState, string>;
  gyms!: Table<Gym, string>;
  exercisePhotos!: Table<ExercisePhoto, string>;
  userSettings!: Table<UserSetting, string>;
  coachMessages!: Table<CoachMessage, string>;
  bodyMetrics!: Table<BodyMetric, string>;
  progressPhotos!: Table<ProgressPhoto, string>;

  constructor() {
    super('gymlog');
    this.version(1).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
    });
    this.version(2).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
    });
    this.version(3).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
    });
    this.version(4).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
      syncState: 'key',
    });
    this.version(5).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, gymId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
      syncState: 'key',
      gyms: 'id, userId, nombre, deletedAt',
    });
    this.version(6).stores({
      exercises: 'id, userId, grupoMuscular, nombre, deletedAt',
      routines: 'id, userId, nombre, deletedAt',
      routineDays: 'id, routineId, orden, deletedAt',
      routineExercises: 'id, routineId, routineDayId, exerciseId, orden, deletedAt',
      workoutSessions: 'id, userId, routineDayId, gymId, fecha, deletedAt',
      loggedExercises: 'id, sessionId, exerciseId, orden, deletedAt',
      loggedSets: 'id, loggedExerciseId, orden, deletedAt',
      syncState: 'key',
      gyms: 'id, userId, nombre, deletedAt',
    }).upgrade(async (tx) => {
      const days = await tx.table('routineDays').toArray();
      const routineIdByDay = new Map<string, string>(days.map((d) => [d.id, d.routineId]));
      await tx.table('routineExercises').toCollection().modify((re) => {
        re.routineId = routineIdByDay.get(re.routineDayId) ?? '';
        re.updatedAt = Date.now(); // re-sincroniza el cambio
      });
    });
    this.version(7).stores({
      routineDays: null, // eliminar la tabla (datos ya migrados a routineExercises.routineId en v6)
      routineExercises: 'id, routineId, exerciseId, orden, deletedAt',
    });
    this.version(8).stores({
      exercisePhotos: 'id, exerciseId, deletedAt',
    });
    // v9: orden manual de rutinas (para la rotación "siguiente rutina").
    // Rellena `orden` por nombre alfabético como punto de partida estable.
    this.version(9).stores({}).upgrade(async (tx) => {
      const rs = await tx.table('routines').toArray();
      const activas = rs
        .filter((r) => r.deletedAt === null)
        .sort((a, b) => a.nombre.localeCompare(b.nombre, 'es', { sensitivity: 'base' }));
      for (let i = 0; i < activas.length; i++) {
        await tx.table('routines').update(activas[i].id, { orden: i, updatedAt: Date.now() });
      }
    });
    // v10: sanea decimales en campos que el servidor guarda como integer
    // (series/reps/descanso de rutina y reps de serie); si no, el push rompe el sync.
    this.version(10).stores({}).upgrade(async (tx) => {
      const esDecimal = (x: unknown): x is number => typeof x === 'number' && !Number.isInteger(x);
      await tx.table('routineExercises').toCollection().modify((re) => {
        let cambiado = false;
        for (const k of ['seriesObjetivo', 'repsObjetivo', 'descansoSegundos'] as const) {
          if (esDecimal(re[k])) { re[k] = Math.round(re[k]); cambiado = true; }
        }
        if (cambiado) re.updatedAt = Date.now();
      });
      await tx.table('loggedSets').toCollection().modify((s) => {
        if (esDecimal(s.reps)) { s.reps = Math.round(s.reps); s.updatedAt = Date.now(); }
      });
    });
    // v11: store de ajustes sincronizados (id = clave del ajuste).
    this.version(11).stores({
      userSettings: 'id, deletedAt',
    });
    // v12: hilo del coach IA, sincronizado.
    this.version(12).stores({
      coachMessages: 'id, createdAt, deletedAt',
    });
    // v13: métricas corporales (peso + medidas), sincronizadas.
    this.version(13).stores({
      bodyMetrics: 'id, tipo, fecha, deletedAt',
    });
    // v14: fotos de progreso corporal, sincronizadas.
    this.version(14).stores({
      progressPhotos: 'id, fecha, angulo, deletedAt',
    });
  }
}

export const db = new GymLogDB();
