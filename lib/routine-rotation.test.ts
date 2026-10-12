import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
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

// Shared with the Android implementation (android/core/domain RoutineRotationTest).
type Fixture = { cases: { name: string; routines: string[]; last: string | null; expected: string | null }[] };
const fixture: Fixture = JSON.parse(
  readFileSync(join(process.cwd(), 'contracts/domain/routine-rotation.json'), 'utf8'),
);

describe('getNextRoutineId (shared fixture contracts/domain/routine-rotation.json)', () => {
  it.each(fixture.cases)('$name', ({ routines, last, expected }) => {
    expect(getNextRoutineId(routines.map((id) => ({ id })), last)).toBe(expected);
  });
});
