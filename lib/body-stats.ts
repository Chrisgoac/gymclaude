import type { BodyMetric } from '@/lib/db/types';

export interface PuntoSerie {
  fecha: string;
  valor: number;
}

export interface ResumenSerie {
  actual: number | null;
  primero: number | null;
  delta: number | null;
  puntos: PuntoSerie[];
}

/**
 * Resume una serie de entradas (activas, orden cronológico asc) de una métrica:
 * valor actual (última), primero, delta = actual - primero (null si <2), y puntos para la gráfica.
 */
export function resumenSerie(metrics: BodyMetric[]): ResumenSerie {
  if (metrics.length === 0) return { actual: null, primero: null, delta: null, puntos: [] };
  const primero = metrics[0].valor;
  const actual = metrics[metrics.length - 1].valor;
  const delta = metrics.length >= 2 ? actual - primero : null;
  const puntos = metrics.map((m) => ({
    fecha: new Date(m.fecha).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit' }),
    valor: m.valor,
  }));
  return { actual, primero, delta, puntos };
}
