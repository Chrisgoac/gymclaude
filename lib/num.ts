// Parseo de campos numéricos de formularios.
// Las columnas series/reps/descanso son enteras en el servidor (Postgres integer);
// guardar decimales rompe el sync. Por eso estos helpers redondean a entero.

/** Entero ≥ 0, o undefined si vacío/no numérico. Para series, reps, descanso (campos opcionales). */
export function parseEnteroOpt(v: string): number | undefined {
  const n = Number(v);
  return v.trim() === '' || Number.isNaN(n) ? undefined : Math.max(0, Math.round(n));
}

/** Entero ≥ 0 (0 si vacío/no numérico). Para reps de una serie (campo obligatorio). */
export function parseEntero(v: string): number {
  return parseEnteroOpt(v) ?? 0;
}

/** Número ≥ 0 admitiendo decimales (0 si vacío/no numérico). Para el peso. */
export function parseDecimal(v: string): number {
  const n = Number(v);
  return v.trim() === '' || Number.isNaN(n) ? 0 : Math.max(0, n);
}
