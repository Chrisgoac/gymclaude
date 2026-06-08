/** Segundos → "m:ss" (p. ej. 65 → "1:05"). Negativos se tratan como 0. */
export function formatSegundos(s: number): string {
  const total = Math.max(0, Math.floor(s));
  const m = Math.floor(total / 60);
  const seg = total % 60;
  return `${m}:${String(seg).padStart(2, '0')}`;
}

/** Texto relativo en días naturales: "hoy", "ayer", "hace N días". */
export function formatHaceDias(ts: number, now: number = Date.now()): string {
  const d0 = new Date(now); d0.setHours(0, 0, 0, 0);
  const d1 = new Date(ts); d1.setHours(0, 0, 0, 0);
  const dias = Math.round((d0.getTime() - d1.getTime()) / 86_400_000);
  if (dias <= 0) return 'hoy';
  if (dias === 1) return 'ayer';
  return `hace ${dias} días`;
}
