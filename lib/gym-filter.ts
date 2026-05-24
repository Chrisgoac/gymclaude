'use client';

import { useEffect, useState } from 'react';

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

/** Hook React: lee el filtro y se re-renderiza cuando cambia (misma pestaña). */
export function useGymFilter(): [GymFilter, (v: GymFilter) => void] {
  const [filtro, setFiltro] = useState<GymFilter>('all');
  useEffect(() => {
    setFiltro(getGymFilter());
    const onChange = () => setFiltro(getGymFilter());
    window.addEventListener('gymfilterchange', onChange);
    return () => window.removeEventListener('gymfilterchange', onChange);
  }, []);
  return [filtro, (v) => { setGymFilter(v); setFiltro(v); }];
}

/** Convierte el filtro en el argumento para las stats (undefined = todos). */
export function filtroAGymId(f: GymFilter): string | undefined {
  return f === 'all' ? undefined : f;
}
