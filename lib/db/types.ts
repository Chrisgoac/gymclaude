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

export const MUSCLE_GROUPS: MuscleGroup[] = [
  'pecho', 'espalda', 'hombros', 'biceps', 'triceps',
  'cuadriceps', 'femoral', 'gluteo', 'gemelo', 'abdomen', 'antebrazo', 'otro',
];

export const EQUIPMENTS: Equipment[] = [
  'barra', 'mancuerna', 'maquina', 'polea', 'peso_corporal', 'otro',
];

export const EXERCISE_TYPES: ExerciseType[] = ['compuesto', 'aislamiento'];
