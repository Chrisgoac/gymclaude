'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { getSetting, setSetting } from '@/lib/repositories/user-settings';

/**
 * Ajuste sincronizado reactivo. Devuelve `fallback` mientras carga o si no existe.
 * El tercer elemento (`loaded`) es false hasta que la lectura terminó: quien actúe según el valor
 * (p. ej. reconciliar logros con el objetivo semanal) debe esperar a `loaded`, no al fallback.
 * El valor se actualiza solo al cambiarlo localmente Y cuando llega por sync (pull).
 */
export function useSetting<T>(clave: string, fallback: T): [T, (v: T) => void, boolean] {
  const result = useLiveQuery(async () => ({ value: await getSetting<T>(clave) }), [clave]);
  const set = (v: T) => { void setSetting(clave, v); };
  return [result?.value ?? fallback, set, result !== undefined];
}
