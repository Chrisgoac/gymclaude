/** LWW de la sync legacy (PWA): solo compara `updatedAt`. La sync v1 usa `decideVersionedWrite`. */
export function resolveServerWrite(existingUpdatedAt: number | undefined, incomingUpdatedAt: number): boolean {
  if (existingUpdatedAt === undefined) return true;
  return incomingUpdatedAt >= existingUpdatedAt;
}

/** Versión lógica de una escritura v1. Debe ordenar igual en TypeScript y en Kotlin. */
export interface VersionTuple {
  updatedAt: number;
  deviceId: string;
  mutationId: string;
}

// Comparación por code units UTF-16 (como `String.compareTo` de Kotlin), nunca por locale.
function compareStrings(a: string, b: string): -1 | 0 | 1 {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Orden lexicográfico (updatedAt, deviceId, mutationId). 0 solo si las tres coinciden. */
export function compareVersionTuple(a: VersionTuple, b: VersionTuple): -1 | 0 | 1 {
  if (a.updatedAt !== b.updatedAt) return a.updatedAt < b.updatedAt ? -1 : 1;
  return compareStrings(a.deviceId, b.deviceId) || compareStrings(a.mutationId, b.mutationId);
}

/** Estado guardado de la fila. device/mutation pueden faltar (escritores legacy): cuentan como ''. */
export interface StoredVersion {
  updatedAt: number;
  deviceId: string | null;
  mutationId: string | null;
  serverVersion: bigint | null;
}

export type ConflictReason = 'STALE_BASE' | 'SUPERSEDED';

export interface WriteDecision {
  apply: boolean;
  conflict: ConflictReason | null;
}

/**
 * Decisión determinista de una escritura v1 (ver contracts/v1/fixtures/conflict-cases.json).
 * Gana el entrante si su tupla es mayor; igual = la misma mutación ya aplicada (no-op). Un
 * tombstone es un registro más. Una base obsoleta nunca bloquea al ganador: solo se reporta
 * (STALE_BASE si se aplica; SUPERSEDED siempre que pierde).
 */
export function decideVersionedWrite(
  existing: StoredVersion | null,
  incoming: VersionTuple,
  baseServerVersion: bigint | null,
): WriteDecision {
  if (!existing) {
    return { apply: true, conflict: baseServerVersion === null ? null : 'STALE_BASE' };
  }
  const cmp = compareVersionTuple(incoming, {
    updatedAt: existing.updatedAt,
    deviceId: existing.deviceId ?? '',
    mutationId: existing.mutationId ?? '',
  });
  if (cmp === 0) return { apply: false, conflict: null };
  if (cmp < 0) return { apply: false, conflict: 'SUPERSEDED' };
  const stale = baseServerVersion === null || baseServerVersion !== existing.serverVersion;
  return { apply: true, conflict: stale ? 'STALE_BASE' : null };
}
