import { ApiError } from '@/lib/api/v1/errors';

// Cursor opaco = base64url (sin relleno) del texto decimal de la `sequence` del diario. Se trabaja
// siempre con bigint/texto: nunca pasa por `number`, así que 2^53 y el máximo de bigint de
// PostgreSQL (2^63 - 1) viajan sin pérdida.

export const MAX_SEQUENCE = BigInt("9223372036854775807");

const invalid = () => new ApiError(400, 'INVALID_CURSOR', 'Cursor is invalid', false);

export function encodeCursor(sequence: bigint): string {
  if (sequence < BigInt(0) || sequence > MAX_SEQUENCE) throw invalid();
  return Buffer.from(sequence.toString(), 'utf8').toString('base64url');
}

/** null o vacío = desde el principio (0). Malformado, negativo o > int64 → 400 INVALID_CURSOR. */
export function decodeCursor(cursor: string | null): bigint {
  if (cursor === null || cursor === '') return BigInt(0);
  if (!/^[A-Za-z0-9_-]+$/.test(cursor) || cursor.length % 4 === 1 || cursor.length > 32) throw invalid();
  const text = Buffer.from(cursor, 'base64url').toString('utf8');
  // Forma canónica: sin ceros a la izquierda ni signo, y debe re-codificar igual (descarta bits sobrantes).
  if (!/^(0|[1-9][0-9]*)$/.test(text) || Buffer.from(text, 'utf8').toString('base64url') !== cursor) throw invalid();
  const value = BigInt(text);
  if (value > MAX_SEQUENCE) throw invalid();
  return value;
}
