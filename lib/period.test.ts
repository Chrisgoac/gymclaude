import { describe, it, expect } from 'vitest';
import { periodoASinceTs, PERIODOS, type Periodo } from '@/lib/period';

const DAY = 24 * 60 * 60 * 1000;
const AHORA = 1_000_000 * DAY; // base determinista

describe('periodoASinceTs', () => {
  it('"todo" devuelve 0 (sin límite inferior)', () => {
    expect(periodoASinceTs('todo', AHORA)).toBe(0);
  });
  it('"4s" resta 28 días', () => {
    expect(periodoASinceTs('4s', AHORA)).toBe(AHORA - 28 * DAY);
  });
  it('"3m" resta 90 días', () => {
    expect(periodoASinceTs('3m', AHORA)).toBe(AHORA - 90 * DAY);
  });
  it('"ano" resta 365 días', () => {
    expect(periodoASinceTs('ano', AHORA)).toBe(AHORA - 365 * DAY);
  });
  it('PERIODOS lista las 4 opciones en orden', () => {
    expect(PERIODOS.map((p) => p.id)).toEqual<Periodo[]>(['4s', '3m', 'ano', 'todo']);
  });
});
