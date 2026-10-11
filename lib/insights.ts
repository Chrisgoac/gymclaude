import type { ExerciseProgressPoint, VolumeByMuscle } from '@/lib/repositories/stats';
import type { MuscleGroup } from '@/lib/db/types';
import { MUSCLE_GROUPS } from '@/lib/db/types';

/** Consejo mostrado cuando un ejercicio está estancado. */
export const DELOAD_CONSEJO = 'Prueba bajar ~10% y vuelve a subir, o cambia de ejercicio.';

export interface Estancamiento {
  estancado: boolean;
  /** Sesiones transcurridas desde la última que batió el máximo histórico de 1RM. */
  sesionesSinMejora: number;
  /** Fecha (epoch ms) de esa última mejora; null si datos insuficientes. */
  ultimaMejoraFecha: number | null;
}

/**
 * Un ejercicio está estancado si su mejor 1RM estimado no mejora en las últimas `n` sesiones:
 * el máximo de las últimas `n` no supera al máximo de las anteriores. Requiere ≥ n+1 sesiones.
 * `points` debe venir en orden cronológico (como los devuelve getExerciseProgress).
 */
export function detectarEstancamiento(points: ExerciseProgressPoint[], n = 3): Estancamiento {
  if (points.length < n + 1) {
    return { estancado: false, sesionesSinMejora: 0, ultimaMejoraFecha: null };
  }
  let runningMax = -Infinity;
  let lastImprovementIdx = 0;
  for (let i = 0; i < points.length; i++) {
    if (points[i].mejor1RM > runningMax) {
      runningMax = points[i].mejor1RM;
      lastImprovementIdx = i;
    }
  }
  const sesionesSinMejora = points.length - 1 - lastImprovementIdx;
  const ultimaMejoraFecha = points[lastImprovementIdx].fecha;
  const mejorReciente = Math.max(...points.slice(points.length - n).map((p) => p.mejor1RM));
  const mejorPrevio = Math.max(...points.slice(0, points.length - n).map((p) => p.mejor1RM));
  return { estancado: mejorReciente <= mejorPrevio, sesionesSinMejora, ultimaMejoraFecha };
}

const DIA = 86400000;
/** Un grupo sin entrenar más de estos días se marca como descuidado. */
export const UMBRAL_DESCUIDADO_DIAS = 10;

export interface FilaEquilibrio {
  grupo: MuscleGroup;
  /** Volumen (kg·rep) del periodo. */
  volumen: number;
  /** Días desde la última sesión que lo entrenó; null si nunca (o sin `lastTrained`). */
  diasSinEntrenar: number | null;
  descuidado: boolean;
  /** % de la meta semanal de volumen cumplido (tope 100); null sin meta o sin volumen semanal. */
  metaPct: number | null;
}

/**
 * Filas del equilibrio muscular (Progreso): grupos con volumen en el periodo o, si hay `lastTrained`,
 * entrenados alguna vez (afloran los descuidados), ordenados por volumen desc (estable en el orden de
 * MUSCLE_GROUPS). Pura; `ahora` lo pasa el llamante.
 */
export function filasEquilibrio(input: {
  data: VolumeByMuscle[];
  lastTrained?: Record<MuscleGroup, number | null>;
  ahora?: number;
  objetivos?: Partial<Record<MuscleGroup, number>>;
  volumenSemana?: Record<MuscleGroup, number>;
}): FilaEquilibrio[] {
  const { data, lastTrained, objetivos, volumenSemana } = input;
  const volByGrupo = new Map(data.map((d) => [d.grupo, d.volumen]));
  const ts = input.ahora ?? 0;
  return MUSCLE_GROUPS
    .filter((g) => volByGrupo.has(g) || (lastTrained ? lastTrained[g] != null : false))
    .sort((a, b) => (volByGrupo.get(b) ?? 0) - (volByGrupo.get(a) ?? 0))
    .map((g) => {
      const volumen = volByGrupo.get(g) ?? 0;
      const ult = lastTrained?.[g] ?? null;
      const dias = ult == null ? null : Math.floor((ts - ult) / DIA);
      const descuidado = lastTrained != null && (ult == null || (dias !== null && dias > UMBRAL_DESCUIDADO_DIAS));
      const meta = objetivos?.[g];
      const volSem = volumenSemana?.[g] ?? 0;
      const metaPct = meta && meta > 0 && volumenSemana != null ? Math.min(100, Math.round((volSem / meta) * 100)) : null;
      return { grupo: g, volumen, diasSinEntrenar: dias, descuidado, metaPct };
    });
}
