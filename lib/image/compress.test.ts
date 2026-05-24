import { describe, it, expect } from 'vitest';
import { calcularDimensiones } from '@/lib/image/compress';

describe('calcularDimensiones', () => {
  it('no agranda imágenes pequeñas', () => {
    expect(calcularDimensiones(800, 600, 1024)).toEqual({ width: 800, height: 600 });
  });
  it('reduce el lado mayor a max manteniendo proporción (horizontal)', () => {
    expect(calcularDimensiones(2048, 1024, 1024)).toEqual({ width: 1024, height: 512 });
  });
  it('reduce el lado mayor a max manteniendo proporción (vertical)', () => {
    expect(calcularDimensiones(1000, 4000, 1024)).toEqual({ width: 256, height: 1024 });
  });
});
