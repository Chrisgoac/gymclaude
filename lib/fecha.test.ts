import { describe, it, expect } from 'vitest';
import { formatHaceDias } from '@/lib/fecha';

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
