import { describe, it, expect } from 'vitest';
import { inferirSalto, INCREMENTO_DEFAULTS } from '@/lib/progresion';

const defaults = INCREMENTO_DEFAULTS;

describe('inferirSalto', () => {
  it('GCD de las diferencias entre pesos distintos', () => {
    expect(inferirSalto([40, 45, 50, 60], { equipamiento: 'maquina', defaults })).toBe(5);
  });
  it('soporta saltos de 2,5', () => {
    expect(inferirSalto([40, 42.5, 45], { equipamiento: 'barra', defaults })).toBe(2.5);
  });
  it('redondea ruido al valor sano más cercano', () => {
    expect(inferirSalto([40, 47.5], { equipamiento: 'maquina', defaults })).toBe(7.5);
  });
  it('sin historial suficiente → default por equipamiento', () => {
    expect(inferirSalto([], { equipamiento: 'mancuerna', defaults })).toBe(2);
    expect(inferirSalto([40], { equipamiento: 'maquina', defaults })).toBe(5);
  });
  it('ignora pesos 0 y duplicados', () => {
    expect(inferirSalto([0, 40, 40, 45], { equipamiento: 'barra', defaults })).toBe(5);
  });
  it('el override gana sobre la inferencia', () => {
    expect(inferirSalto([40, 45, 50], { equipamiento: 'maquina', defaults, override: 1.25 })).toBe(1.25);
  });
});
