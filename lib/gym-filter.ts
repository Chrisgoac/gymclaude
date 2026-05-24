'use client';

import { useSyncExternalStore } from 'react';

const KEY = 'gymlog.gymFilter';
export type GymFilter = 'all' | string;

export function getGymFilter(): GymFilter {
  if (typeof localStorage === 'undefined') return 'all';
  return localStorage.getItem(KEY) ?? 'all';
}

export function setGymFilter(value: GymFilter): void {
  if (typeof localStorage === 'undefined') return;
  localStorage.setItem(KEY, value);
  window.dispatchEvent(new CustomEvent('gymfilterchange'));
}

function subscribe(callback: () => void): () => void {
  window.addEventListener('gymfilterchange', callback);
  window.addEventListener('storage', callback); // cambios desde otra pestaña
  return () => {
    window.removeEventListener('gymfilterchange', callback);
    window.removeEventListener('storage', callback);
  };
}

/** Hook React: lee el filtro y se re-renderiza cuando cambia (misma pestaña u otra). */
export function useGymFilter(): [GymFilter, (v: GymFilter) => void] {
  // useSyncExternalStore evita set-state-en-efecto y resuelve el snapshot de servidor (SSR).
  const filtro = useSyncExternalStore(subscribe, getGymFilter, () => 'all');
  return [filtro, setGymFilter];
}

/** Convierte el filtro en el argumento para las stats (undefined = todos). */
export function filtroAGymId(f: GymFilter): string | undefined {
  return f === 'all' ? undefined : f;
}
