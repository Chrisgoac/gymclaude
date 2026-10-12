import { describe, it, expect } from 'vitest';
import { formatHaceDias, formatSegundos } from '@/lib/fecha';

const now = new Date('2026-06-07T10:00:00').getTime();

describe('formatHaceDias', () => {
  it('mismo día → hoy', () => {
    expect(formatHaceDias(new Date('2026-06-07T08:00:00').getTime(), now)).toBe('hoy');
  });
  it('día anterior → ayer', () => {
    expect(formatHaceDias(new Date('2026-06-06T23:00:00').getTime(), now)).toBe('ayer');
  });
  it('varios días → hace N días', () => {
    expect(formatHaceDias(new Date('2026-06-04T10:00:00').getTime(), now)).toBe('hace 3 días');
  });
});

describe('formatSegundos', () => {
  it('formatea segundos como mm:ss', () => {
    expect(formatSegundos(0)).toBe('0:00');
    expect(formatSegundos(5)).toBe('0:05');
    expect(formatSegundos(65)).toBe('1:05');
    expect(formatSegundos(600)).toBe('10:00');
  });
  it('nunca devuelve negativos', () => {
    expect(formatSegundos(-3)).toBe('0:00');
  });
});

// Shared with the Android implementation (android/core/domain DomainParityTest).
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const fechaFixture = JSON.parse(readFileSync(join(process.cwd(), 'contracts/domain/fecha-cases.json'), 'utf8'));

describe('shared fixture contracts/domain/fecha-cases.json', () => {
  it.each(fechaFixture.formatSegundos as { seconds: number; expected: string }[])(
    'formatSegundos($seconds)',
    ({ seconds, expected }) => expect(formatSegundos(seconds)).toBe(expected),
  );
  it.each(fechaFixture.formatHaceDias as { zone: string; now: number; ts: number; expected: string }[])(
    'formatHaceDias $zone $ts vs $now',
    ({ zone, now, ts, expected }) => {
      const previous = process.env.TZ;
      process.env.TZ = zone;
      try {
        expect(formatHaceDias(ts, now)).toBe(expected);
      } finally {
        if (previous === undefined) delete process.env.TZ; else process.env.TZ = previous;
      }
    },
  );
});
