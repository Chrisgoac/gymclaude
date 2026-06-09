'use client';

import { useSyncExternalStore } from 'react';
import type { Equipment } from '@/lib/db/types';
import type { ModoProgresion } from '@/lib/progresion';
import { INCREMENTO_DEFAULTS } from '@/lib/progresion';
import { useSetting } from '@/lib/use-setting';
import { getSetting, setSetting } from '@/lib/repositories/user-settings';

const KEY = 'gymlog.suggestNextRoutine';
const EVENT = 'settingschange';

const MODOS: ModoProgresion[] = ['doble', 'objetivo', 'repite', 'off'];

/** ¿Sugerir la siguiente rutina en rotación en la pantalla de Entrenar? Default: false. */
export function getSuggestNextRoutine(): boolean {
  if (typeof localStorage === 'undefined') return false;
  return localStorage.getItem(KEY) === '1';
}

export function setSuggestNextRoutine(value: boolean): void {
  if (typeof localStorage === 'undefined') return;
  localStorage.setItem(KEY, value ? '1' : '0');
  window.dispatchEvent(new CustomEvent(EVENT));
}

function subscribe(callback: () => void): () => void {
  window.addEventListener(EVENT, callback);
  window.addEventListener('storage', callback); // cambios desde otra pestaña
  return () => {
    window.removeEventListener(EVENT, callback);
    window.removeEventListener('storage', callback);
  };
}

/** Hook React: lee el ajuste y se re-renderiza al cambiar (misma pestaña u otra). */
export function useSuggestNextRoutine(): [boolean, (v: boolean) => void] {
  const value = useSyncExternalStore(subscribe, getSuggestNextRoutine, () => false);
  return [value, setSuggestNextRoutine];
}

/** Modo de progresión (sincronizado). Default 'objetivo'; valida contra MODOS. */
export function useModoProgresion(): [ModoProgresion, (v: ModoProgresion) => void] {
  const [raw, set] = useSetting<ModoProgresion>('modoProgresion', 'objetivo');
  const value = MODOS.includes(raw) ? raw : 'objetivo';
  return [value, set];
}

/** Incrementos por equipamiento (sincronizado). Se fusiona sobre los defaults. */
export function useIncrementos(): [Record<Equipment, number>, (p: Partial<Record<Equipment, number>>) => void] {
  const [stored] = useSetting<Partial<Record<Equipment, number>>>('incrementos', {});
  const value = { ...INCREMENTO_DEFAULTS, ...stored };
  // Lee el valor actual de Dexie en el momento de escribir (no del render) para no
  // perder ediciones rápidas en filas distintas.
  const setPartial = (p: Partial<Record<Equipment, number>>) => {
    void (async () => {
      const current = (await getSetting<Partial<Record<Equipment, number>>>('incrementos')) ?? {};
      await setSetting('incrementos', { ...current, ...p });
    })();
  };
  return [value, setPartial];
}
