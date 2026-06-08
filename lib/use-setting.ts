'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { getSetting, setSetting } from '@/lib/repositories/user-settings';

/**
 * Ajuste sincronizado reactivo. Devuelve `fallback` mientras carga o si no existe.
 * El valor se actualiza solo al cambiarlo localmente Y cuando llega por sync (pull).
 */
export function useSetting<T>(clave: string, fallback: T): [T, (v: T) => void] {
  const value = useLiveQuery(() => getSetting<T>(clave), [clave]);
  const set = (v: T) => { void setSetting(clave, v); };
  return [value ?? fallback, set];
}
