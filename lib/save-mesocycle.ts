import {
  MUSCLE_GROUPS, EQUIPMENTS, EXERCISE_TYPES,
  type MuscleGroup, type Equipment, type ExerciseType,
} from '@/lib/db/types';
import { listExercises, createExercise } from '@/lib/repositories/exercises';
import { createRoutine, addExerciseToRoutine, setRoutineMesocycle } from '@/lib/repositories/routines';
import { createMesocycle } from '@/lib/repositories/mesocycles';
import type { PropuestaMesociclo, EjercicioPropuesto } from '@/lib/meso-prompt';

/** Normaliza un nombre para emparejar ejercicios: minúsculas, sin acentos, sin espacios extra. */
function norm(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim().replace(/\s+/g, ' ');
}

function comoGrupo(v: string): MuscleGroup {
  return (MUSCLE_GROUPS as string[]).includes(v) ? (v as MuscleGroup) : 'otro';
}
function comoEquip(v: string): Equipment {
  return (EQUIPMENTS as string[]).includes(v) ? (v as Equipment) : 'otro';
}
function comoTipo(v: string): ExerciseType {
  return (EXERCISE_TYPES as string[]).includes(v) ? (v as ExerciseType) : 'compuesto';
}

/**
 * Guarda una propuesta de mesociclo: crea el Mesocycle, y por cada día una rutina
 * etiquetada con su mesocycleId con sus ejercicios (reutilizando los del catálogo por
 * nombre normalizado, creando los que falten). Devuelve el id del mesociclo.
 */
export async function guardarMesociclo(propuesta: PropuestaMesociclo): Promise<string> {
  const meso = await createMesocycle({
    nombre: propuesta.nombre,
    objetivo: propuesta.objetivo,
    semanas: propuesta.semanas,
    diasPorSemana: propuesta.diasPorSemana,
    notas: propuesta.notas ?? null,
    progresion: propuesta.progresion,
    fechaInicio: Date.now(),
  });

  // Índice nombre normalizado → id de los ejercicios existentes.
  const existentes = await listExercises();
  const porNombre = new Map<string, string>();
  for (const e of existentes) porNombre.set(norm(e.nombre), e.id);

  async function resolverEjercicio(e: EjercicioPropuesto): Promise<string> {
    const clave = norm(e.nombre);
    const hit = porNombre.get(clave);
    if (hit) return hit;
    const nuevo = await createExercise({
      nombre: e.nombre.trim(),
      grupoMuscular: comoGrupo(e.grupoMuscular),
      equipamiento: comoEquip(e.equipamiento),
      tipo: comoTipo(e.tipo),
    });
    porNombre.set(clave, nuevo.id); // por si se repite en otro día
    return nuevo.id;
  }

  const dias = [...propuesta.dias].sort((a, b) => a.orden - b.orden);
  for (const dia of dias) {
    const rutina = await createRoutine({ nombre: dia.nombre });
    await setRoutineMesocycle(rutina.id, meso.id);
    for (const ej of dia.ejercicios) {
      const exerciseId = await resolverEjercicio(ej);
      await addExerciseToRoutine(rutina.id, {
        exerciseId,
        seriesObjetivo: ej.seriesObjetivo,
        repsObjetivo: ej.repsObjetivo,
        descansoSegundos: ej.descansoSegundos,
      });
    }
  }
  return meso.id;
}
