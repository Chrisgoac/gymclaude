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
  descripcion?: string; // catálogo: posición inicial / overview
  execution?: string;   // catálogo: pasos de ejecución
  /** Override manual del salto de peso al progresar (kg). Gana sobre la inferencia. */
  incrementoKg?: number;
  esPersonalizado: boolean;
}

export interface ExercisePhoto extends SyncMeta {
  userId: string | null;
  exerciseId: string;
  url: string;   // URL pública en R2
  key: string;   // object key en R2
}

export type AnguloFoto = 'frente' | 'lado' | 'espalda';

export interface ProgressPhoto extends SyncMeta {
  userId: string | null;
  url: string;
  key: string;
  fecha: number;
  angulo: AnguloFoto;
  nota: string | null;
}

export const MUSCLE_GROUPS: MuscleGroup[] = [
  'pecho', 'espalda', 'hombros', 'biceps', 'triceps',
  'cuadriceps', 'femoral', 'gluteo', 'gemelo', 'abdomen', 'antebrazo', 'otro',
];

export const EQUIPMENTS: Equipment[] = [
  'barra', 'mancuerna', 'maquina', 'polea', 'peso_corporal', 'otro',
];

export const EXERCISE_TYPES: ExerciseType[] = ['compuesto', 'aislamiento'];

export interface SemanaPlan {
  semana: number;
  descarga: boolean;
  ajuste: string;
}

export interface Mesocycle extends SyncMeta {
  userId: string | null;
  nombre: string;
  objetivo: string;
  semanas: number;
  diasPorSemana: number;
  notas: string | null;
  progresion: SemanaPlan[];
  fechaInicio: number;
}

export interface Routine extends SyncMeta {
  userId: string | null;
  nombre: string;
  descripcion?: string;
  orden: number;
  archivada: boolean;
  mesocycleId?: string | null;
}

export interface RoutineExercise extends SyncMeta {
  routineId: string;
  exerciseId: string;
  orden: number;
  seriesObjetivo?: number;
  repsObjetivo?: number;
  /** Tope inferior del rango de reps (doble progresión). Sin valor → se asume tope−4. */
  repsObjetivoMin?: number;
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

/** Ajuste de usuario sincronizado. OJO: `id` ES la clave del ajuste (ej. 'modoProgresion'),
 *  no un UUID — así dos dispositivos convergen por LWW sobre la misma fila. */
export interface UserSetting extends SyncMeta {
  /** null en local hasta el primer push (igual que el resto de entidades). */
  userId: string | null;
  /** Valor serializado como JSON (número, booleano u objeto). */
  valor: string;
}

export interface CoachMessage extends SyncMeta {
  userId: string | null;
  rol: 'user' | 'assistant';
  contenido: string;
  /** epoch ms; ordena el hilo. */
  createdAt: number;
}

export interface BodyMetric extends SyncMeta {
  userId: string | null;
  /** clave predefinida ('peso','cintura',...) o personalizada. */
  tipo: string;
  /** kg para peso, cm para medidas (o la unidad de la personalizada). */
  valor: number;
  /** epoch ms; ordena la serie. */
  fecha: number;
}

export interface Achievement extends SyncMeta {
  userId: string | null;
  clave: string;
  fechaDesbloqueo: number;
}
