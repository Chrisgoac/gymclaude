/** Metadatos comunes para sincronización (Fase 4). En Fase 1 ya se rellenan. */
export interface SyncMeta {
  id: string; // UUID generado en cliente (crypto.randomUUID)
  updatedAt: number; // epoch ms
  deletedAt: number | null; // tombstone: null = activo
}

export type MuscleGroup =
  | 'pecho' | 'espalda' | 'hombros' | 'biceps' | 'triceps'
  | 'cuadriceps' | 'femoral' | 'gluteo' | 'gemelo' | 'abdomen' | 'antebrazo' | 'otro';

export type Equipment =
  | 'barra' | 'mancuerna' | 'maquina' | 'polea' | 'peso_corporal' | 'otro';

export type ExerciseType = 'compuesto' | 'aislamiento';

export interface Exercise extends SyncMeta {
  /** null = catálogo global precargado. En Fase 4 se asigna el id de usuario. */
  userId: string | null;
  nombre: string;
  grupoMuscular: MuscleGroup;
  equipamiento: Equipment;
  tipo: ExerciseType;
  videoUrl?: string;
  notas?: string;
  esPersonalizado: boolean;
}

export interface ExercisePhoto extends SyncMeta {
  userId: string | null;
  exerciseId: string;
  url: string;   // URL pública en R2
  key: string;   // object key en R2
}

export const MUSCLE_GROUPS: MuscleGroup[] = [
  'pecho', 'espalda', 'hombros', 'biceps', 'triceps',
  'cuadriceps', 'femoral', 'gluteo', 'gemelo', 'abdomen', 'antebrazo', 'otro',
];

export const EQUIPMENTS: Equipment[] = [
  'barra', 'mancuerna', 'maquina', 'polea', 'peso_corporal', 'otro',
];

export const EXERCISE_TYPES: ExerciseType[] = ['compuesto', 'aislamiento'];

export interface Routine extends SyncMeta {
  userId: string | null;
  nombre: string;
  descripcion?: string;
  orden: number;
  archivada: boolean;
}

export interface RoutineExercise extends SyncMeta {
  routineId: string;
  exerciseId: string;
  orden: number;
  seriesObjetivo?: number;
  repsObjetivo?: number;
  descansoSegundos?: number;
  notas?: string;
}

export interface Gym extends SyncMeta {
  userId: string | null;
  nombre: string;
  orden: number;
  archivada: boolean;
}

export interface WorkoutSession extends SyncMeta {
  userId: string | null;
  routineDayId?: string; // legacy sin uso
  routineId?: string | null; // null/ausente = entreno libre
  gymId?: string | null; // null/ausente = "Sin gimnasio" (datos pre-migración)
  fecha: number; // epoch ms (inicio del entreno)
  duracionSegundos?: number;
  notas?: string;
}

export interface LoggedExercise extends SyncMeta {
  sessionId: string;
  exerciseId: string;
  orden: number;
}

export interface LoggedSet extends SyncMeta {
  loggedExerciseId: string;
  orden: number;
  peso: number;
  reps: number;
  esCalentamiento?: boolean;
}

export interface SyncState {
  key: string;
  value: number;
}
