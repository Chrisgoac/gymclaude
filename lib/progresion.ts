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

export type ModoProgresion = 'doble' | 'objetivo' | 'repite' | 'off';

export type Motivo = 'subio-peso' | 'subio-reps' | 'repite' | 'sin-historial' | 'libre' | 'off';

export interface Sugerencia {
  pesoSugerido: number;
  repsSugeridas: number;
  motivo: Motivo;
}

export interface SugerenciaInput {
  modo: ModoProgresion;
  /** Series de trabajo (sin calentamiento) de la última sesión del ejercicio, mismo gym. undefined = sin historial. */
  ultimo?: { peso: number; reps: number }[];
  /** Objetivo de la rutina. undefined = entreno libre. */
  objetivo?: { repsObjetivo?: number; repsObjetivoMin?: number };
  /** Salto de peso resuelto por inferirSalto. */
  salto: number;
  esCorporal: boolean;
}

function rango(objetivo: { repsObjetivo?: number; repsObjetivoMin?: number }): { min: number; tope: number } {
  const tope = objetivo.repsObjetivo ?? 0;
  const minRaw = objetivo.repsObjetivoMin ?? tope - 4;
  const min = Math.max(1, Math.min(minRaw, tope));
  return { min, tope };
}

export function calcularSugerencia(input: SugerenciaInput): Sugerencia {
  const { modo, ultimo, objetivo, salto, esCorporal } = input;
  const ultimoSet = ultimo && ultimo.length > 0 ? ultimo[ultimo.length - 1] : undefined;

  if (modo === 'off') {
    return { pesoSugerido: ultimoSet?.peso ?? 0, repsSugeridas: ultimoSet?.reps ?? 0, motivo: 'off' };
  }
  if (!objetivo || !objetivo.repsObjetivo) {
    return { pesoSugerido: ultimoSet?.peso ?? 0, repsSugeridas: ultimoSet?.reps ?? 0, motivo: 'libre' };
  }
  if (!ultimo || ultimo.length === 0) {
    return { pesoSugerido: 0, repsSugeridas: objetivo.repsObjetivo ?? 0, motivo: 'sin-historial' };
  }

  const basePeso = ultimoSet!.peso;
  const { min, tope } = rango(objetivo);
  const objetivoReps = modo === 'doble' ? tope : (objetivo.repsObjetivo ?? 0);
  const exito = ultimo.every((s) => s.reps >= objetivoReps);

  if (esCorporal) {
    if (exito) return { pesoSugerido: basePeso, repsSugeridas: ultimoSet!.reps + 1, motivo: 'subio-reps' };
    return { pesoSugerido: basePeso, repsSugeridas: objetivoReps, motivo: 'repite' };
  }

  if (modo === 'doble') {
    if (exito) return { pesoSugerido: basePeso + salto, repsSugeridas: min, motivo: 'subio-peso' };
    return { pesoSugerido: basePeso, repsSugeridas: Math.min(tope, ultimoSet!.reps + 1), motivo: 'subio-reps' };
  }

  // modo 'objetivo' | 'repite'
  if (exito) return { pesoSugerido: basePeso + salto, repsSugeridas: objetivoReps, motivo: 'subio-peso' };
  return { pesoSugerido: basePeso, repsSugeridas: objetivoReps, motivo: 'repite' };
}

/**
 * Texto corto para el badge de la card de entreno. null = no mostrar badge.
 * El `salto` debe ser el mismo valor pasado a `calcularSugerencia` para que el badge y el peso sugerido sean coherentes.
 */
export function describeMotivo(s: Sugerencia, salto: number): string | null {
  switch (s.motivo) {
    case 'subio-peso': return `▲ +${salto} kg`;
    case 'subio-reps': return '▲ +1 rep';
    case 'repite': return '= repite';
    default: return null;
  }
}
