'use client';

import { useSyncExternalStore } from 'react';
import type { Equipment } from '@/lib/db/types';
import type { ModoProgresion } from '@/lib/progresion';
import { INCREMENTO_DEFAULTS } from '@/lib/progresion';

const KEY = 'gymlog.suggestNextRoutine';
const EVENT = 'settingschange';

const KEY_MODO = 'gymlog.modoProgresion';
const KEY_INCR = 'gymlog.incrementos';
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

export function getModoProgresion(): ModoProgresion {
  if (typeof localStorage === 'undefined') return 'objetivo';
  const v = localStorage.getItem(KEY_MODO);
  return (MODOS as string[]).includes(v ?? '') ? (v as ModoProgresion) : 'objetivo';
}

export function setModoProgresion(value: ModoProgresion): void {
  if (typeof localStorage === 'undefined') return;
  localStorage.setItem(KEY_MODO, value);
  window.dispatchEvent(new CustomEvent(EVENT));
}

export function getIncrementos(): Record<Equipment, number> {
  if (typeof localStorage === 'undefined') return { ...INCREMENTO_DEFAULTS };
  try {
    const raw = localStorage.getItem(KEY_INCR);
    if (!raw) return { ...INCREMENTO_DEFAULTS };
    return { ...INCREMENTO_DEFAULTS, ...(JSON.parse(raw) as Partial<Record<Equipment, number>>) };
  } catch {
    return { ...INCREMENTO_DEFAULTS };
  }
}

export function setIncrementos(partial: Partial<Record<Equipment, number>>): void {
  if (typeof localStorage === 'undefined') return;
  localStorage.setItem(KEY_INCR, JSON.stringify({ ...getIncrementos(), ...partial }));
  window.dispatchEvent(new CustomEvent(EVENT));
}

export function useModoProgresion(): [ModoProgresion, (v: ModoProgresion) => void] {
  const value = useSyncExternalStore(subscribe, getModoProgresion, () => 'objetivo' as ModoProgresion);
  return [value, setModoProgresion];
}

export function useIncrementos(): [Record<Equipment, number>, (p: Partial<Record<Equipment, number>>) => void] {
  const value = useSyncExternalStore(subscribe, getIncrementos, () => ({ ...INCREMENTO_DEFAULTS }));
  return [value, setIncrementos];
}
