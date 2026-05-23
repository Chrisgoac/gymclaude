import type { MuscleGroup, Equipment, ExerciseType } from '@/lib/db/types';

export const muscleGroupLabel: Record<MuscleGroup, string> = {
  pecho: 'Pecho', espalda: 'Espalda', hombros: 'Hombros', biceps: 'Bíceps',
  triceps: 'Tríceps', cuadriceps: 'Cuádriceps', femoral: 'Femoral', gluteo: 'Glúteo',
  gemelo: 'Gemelo', abdomen: 'Abdomen', antebrazo: 'Antebrazo', otro: 'Otro',
};

export const equipmentLabel: Record<Equipment, string> = {
  barra: 'Barra', mancuerna: 'Mancuerna', maquina: 'Máquina', polea: 'Polea',
  peso_corporal: 'Peso corporal', otro: 'Otro',
};

export const exerciseTypeLabel: Record<ExerciseType, string> = {
  compuesto: 'Compuesto', aislamiento: 'Aislamiento',
};
