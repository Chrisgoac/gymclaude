'use client';

import { useSyncExternalStore } from 'react';

const KEY = 'gymlog.suggestNextRoutine';
const EVENT = 'settingschange';

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
