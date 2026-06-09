import { describe, it, expect } from 'vitest';
import { ANGULOS, anguloLabel } from '@/lib/progress-photos';

describe('catálogo de ángulos', () => {
  it('ANGULOS en orden frente, lado, espalda', () => {
    expect(ANGULOS).toEqual(['frente', 'lado', 'espalda']);
  });
  it('anguloLabel capitaliza cada ángulo', () => {
    expect(anguloLabel.frente).toBe('Frente');
    expect(anguloLabel.lado).toBe('Lado');
    expect(anguloLabel.espalda).toBe('Espalda');
  });
});
