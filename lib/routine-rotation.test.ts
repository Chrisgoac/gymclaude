import { describe, it, expect } from 'vitest';
import { getNextRoutineId } from '@/lib/routine-rotation';

const rs = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];

describe('getNextRoutineId', () => {
  it('lista vacía → null', () => {
    expect(getNextRoutineId([], 'a')).toBeNull();
    expect(getNextRoutineId([], null)).toBeNull();
  });

  it('sin última → la primera', () => {
    expect(getNextRoutineId(rs, null)).toBe('a');
  });

  it('última desconocida (borrada) → la primera', () => {
    expect(getNextRoutineId(rs, 'zzz')).toBe('a');
  });

  it('en medio → la siguiente', () => {
    expect(getNextRoutineId(rs, 'a')).toBe('b');
    expect(getNextRoutineId(rs, 'b')).toBe('c');
  });

  it('la última de la lista → vuelve a la primera (ciclo)', () => {
    expect(getNextRoutineId(rs, 'c')).toBe('a');
  });

  it('una sola rutina → esa misma', () => {
    expect(getNextRoutineId([{ id: 'solo' }], 'solo')).toBe('solo');
  });
});
