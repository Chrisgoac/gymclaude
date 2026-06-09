import type { ExerciseProgressPoint } from '@/lib/repositories/stats';

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
