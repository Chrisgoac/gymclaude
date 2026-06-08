import { describe, it, expect, beforeEach } from 'vitest';
import {
  getSuggestNextRoutine, setSuggestNextRoutine,
  getModoProgresion, setModoProgresion,
  getIncrementos, setIncrementos,
} from '@/lib/settings';
import { INCREMENTO_DEFAULTS } from '@/lib/progresion';

beforeEach(() => localStorage.clear());

describe('suggestNextRoutine', () => {
  it('por defecto es false', () => {
    expect(getSuggestNextRoutine()).toBe(false);
  });
  it('persiste el valor elegido', () => {
    setSuggestNextRoutine(true);
    expect(getSuggestNextRoutine()).toBe(true);
    setSuggestNextRoutine(false);
    expect(getSuggestNextRoutine()).toBe(false);
  });
});

describe('modo de progresión', () => {
  it('default = objetivo', () => {
    expect(getModoProgresion()).toBe('objetivo');
  });
  it('persiste el modo elegido', () => {
    setModoProgresion('doble');
    expect(getModoProgresion()).toBe('doble');
  });
  it('valor corrupto en storage → vuelve al default', () => {
    localStorage.setItem('gymlog.modoProgresion', 'basura');
    expect(getModoProgresion()).toBe('objetivo');
  });
});

describe('incrementos por equipamiento', () => {
  it('default = INCREMENTO_DEFAULTS', () => {
    expect(getIncrementos()).toEqual(INCREMENTO_DEFAULTS);
  });
  it('fusiona overrides parciales sobre los defaults', () => {
    setIncrementos({ maquina: 7.5 });
    expect(getIncrementos().maquina).toBe(7.5);
    expect(getIncrementos().barra).toBe(INCREMENTO_DEFAULTS.barra);
  });
  it('storage corrupto → defaults', () => {
    localStorage.setItem('gymlog.incrementos', '{no json');
    expect(getIncrementos()).toEqual(INCREMENTO_DEFAULTS);
  });
  it('setIncrementos con objeto completo round-trip', () => {
    setIncrementos({ ...INCREMENTO_DEFAULTS, barra: 5 });
    expect(getIncrementos().barra).toBe(5);
    expect(getIncrementos().mancuerna).toBe(INCREMENTO_DEFAULTS.mancuerna);
  });
  it('getIncrementos devuelve la misma referencia si el storage no cambia', () => {
    // Prime the cache with a known stored value, then verify two consecutive reads are the same object.
    setIncrementos({ barra: 10 });
    const a = getIncrementos();
    const b = getIncrementos();
    expect(a).toBe(b);
  });
});
