/**
 * Devuelve la id de la "siguiente" rutina en rotación, dada la lista de rutinas
 * en su orden manual y la id de la última rutina entrenada.
 *
 * - Lista vacía → null.
 * - Sin última (null) o última ya no existe (borrada) → la primera de la lista.
 * - En otro caso → la siguiente en orden, en ciclo (la última vuelve a la primera).
 */
export function getNextRoutineId(
  routinesEnOrden: { id: string }[],
  lastRoutineId: string | null,
): string | null {
  if (routinesEnOrden.length === 0) return null;
  const idx = lastRoutineId == null ? -1 : routinesEnOrden.findIndex((r) => r.id === lastRoutineId);
  if (idx === -1) return routinesEnOrden[0].id;
  return routinesEnOrden[(idx + 1) % routinesEnOrden.length].id;
}
