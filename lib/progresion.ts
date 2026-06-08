import type { Equipment } from '@/lib/db/types';

/** Salto de peso por defecto (kg) según equipamiento. Semilla cuando no hay historial. */
export const INCREMENTO_DEFAULTS: Record<Equipment, number> = {
  barra: 2.5,
  mancuerna: 2,
  maquina: 5,
  polea: 2.5,
  peso_corporal: 0,
  otro: 2.5,
};

/** Saltos plausibles a los que se redondea la inferencia para evitar ruido. */
export const SANE_STEPS = [0.5, 1, 1.25, 2, 2.5, 5, 7.5, 10];

function gcd(a: number, b: number): number {
  return b === 0 ? a : gcd(b, a % b);
}

function snapSano(v: number): number {
  return SANE_STEPS.reduce((best, s) => (Math.abs(s - v) < Math.abs(best - v) ? s : best), SANE_STEPS[0]);
}

/**
 * Deduce el salto de peso real de un ejercicio a partir de los pesos ya registrados.
 * Prioridad: override manual → inferencia (GCD de diferencias) → default por equipamiento.
 */
export function inferirSalto(
  pesos: number[],
  opts: { equipamiento: Equipment; defaults: Record<Equipment, number>; override?: number },
): number {
  if (opts.override != null && opts.override > 0) return opts.override;
  const distintos = [...new Set(pesos.filter((p) => p > 0))].sort((a, b) => a - b);
  if (distintos.length >= 2) {
    // Escalar ×100 para trabajar con enteros (evita errores de coma flotante con 2,5 / 1,25).
    let g = 0;
    for (let i = 1; i < distintos.length; i++) {
      g = gcd(g, Math.round((distintos[i] - distintos[i - 1]) * 100));
    }
    return snapSano(g / 100);
  }
  return opts.defaults[opts.equipamiento] ?? 2.5;
}
