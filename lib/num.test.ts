import { describe, it, expect } from 'vitest';
import { parseEnteroOpt, parseEntero, parseDecimal } from '@/lib/num';

describe('parseEnteroOpt', () => {
  it('vacío → undefined', () => expect(parseEnteroOpt('  ')).toBeUndefined());
  it('no numérico → undefined', () => expect(parseEnteroOpt('abc')).toBeUndefined());
  it('redondea decimales (el bug del sync: 1.5 en columna integer)', () => {
    expect(parseEnteroOpt('1.5')).toBe(2);
    expect(parseEnteroOpt('2.4')).toBe(2);
  });
  it('clampa negativos a 0', () => expect(parseEnteroOpt('-3')).toBe(0));
});

describe('parseEntero', () => {
  it('vacío → 0', () => expect(parseEntero('')).toBe(0));
  it('redondea', () => expect(parseEntero('9.6')).toBe(10));
});

describe('parseDecimal', () => {
  it('admite decimales (peso)', () => expect(parseDecimal('22.5')).toBe(22.5));
  it('vacío → 0', () => expect(parseDecimal('')).toBe(0));
  it('clampa negativos a 0', () => expect(parseDecimal('-1')).toBe(0));
});
