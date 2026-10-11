export type Periodo = '4s' | '3m' | 'ano' | 'todo';

export const PERIODOS: { id: Periodo; label: string }[] = [
  { id: '4s', label: '4 sem' },
  { id: '3m', label: '3 meses' },
  { id: 'ano', label: 'Año' },
  { id: 'todo', label: 'Todo' },
];

const DAY = 24 * 60 * 60 * 1000;
const DIAS: Record<Periodo, number> = { '4s': 28, '3m': 90, ano: 365, todo: 0 };

/** Devuelve el timestamp de inicio del periodo. 0 = sin límite ("Todo"). */
export function periodoASinceTs(p: Periodo, ahora: number = Date.now()): number {
  return DIAS[p] === 0 ? 0 : ahora - DIAS[p] * DAY;
}

/** Lunes 00:00 (hora local) de la semana que contiene ts. */
export function inicioSemana(ts: number): number {
  const d = new Date(ts);
  d.setHours(0, 0, 0, 0);
  const dow = (d.getDay() + 6) % 7; // 0 = lunes
  d.setDate(d.getDate() - dow);
  return d.getTime();
}
