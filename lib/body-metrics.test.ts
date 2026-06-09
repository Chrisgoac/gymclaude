import { describe, it, expect } from 'vitest';
import {
  METRICAS_PREDEF,
  ORDEN_PREDEF,
  slugify,
  resolverMetrica,
  type MetricaPersonalizada,
} from '@/lib/body-metrics';

describe('METRICAS_PREDEF', () => {
  it('peso está en kg y las medidas en cm', () => {
    expect(METRICAS_PREDEF.peso).toEqual({ label: 'Peso', unidad: 'kg' });
    expect(METRICAS_PREDEF.cintura.unidad).toBe('cm');
  });
  it('ORDEN_PREDEF empieza por peso y cubre todas las claves', () => {
    expect(ORDEN_PREDEF[0]).toBe('peso');
    expect([...ORDEN_PREDEF].sort()).toEqual(Object.keys(METRICAS_PREDEF).sort());
  });
});

describe('slugify', () => {
  it('normaliza acentos, espacios y mayúsculas', () => {
    expect(slugify('Brazo Derecho')).toBe('brazo-derecho');
    expect(slugify('  Glúteo  ')).toBe('gluteo');
  });
  it('colapsa caracteres no alfanuméricos', () => {
    expect(slugify('% grasa!!')).toBe('grasa');
  });
});

describe('resolverMetrica', () => {
  const pers: MetricaPersonalizada[] = [{ clave: 'grasa', label: '% Grasa', unidad: '%' }];
  it('resuelve una predefinida', () => {
    expect(resolverMetrica('cintura', pers)).toEqual({ label: 'Cintura', unidad: 'cm' });
  });
  it('resuelve una personalizada', () => {
    expect(resolverMetrica('grasa', pers)).toEqual({ label: '% Grasa', unidad: '%' });
  });
  it('cae al fallback para una desconocida', () => {
    expect(resolverMetrica('xyz', pers)).toEqual({ label: 'xyz', unidad: '' });
  });
  it('predefinida tiene prioridad sobre personalizada con la misma clave', () => {
    expect(resolverMetrica('peso', [{ clave: 'peso', label: 'X', unidad: 'lb' }])).toEqual({
      label: 'Peso', unidad: 'kg',
    });
  });
});
