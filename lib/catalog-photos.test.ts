import { describe, it, expect } from 'vitest';
import { resolveExercisePhotoUrl, CATALOG_PHOTOS } from '@/lib/catalog-photos';

describe('resolveExercisePhotoUrl', () => {
  it('prioriza la foto del usuario sobre la de por defecto', () => {
    expect(resolveExercisePhotoUrl('seed-press-banca', 'https://r2/mia.jpg')).toBe('https://r2/mia.jpg');
  });
  it('usa la de por defecto si el usuario no tiene', () => {
    expect(resolveExercisePhotoUrl('seed-press-banca')).toBe('/catalog/press-banca.png');
  });
  it('devuelve undefined si no hay ninguna', () => {
    expect(resolveExercisePhotoUrl('custom-xyz')).toBeUndefined();
    expect(resolveExercisePhotoUrl('custom-xyz', undefined)).toBeUndefined();
  });
  it('cubre los 143 ejercicios del catálogo', () => {
    expect(Object.keys(CATALOG_PHOTOS)).toHaveLength(143);
  });
});
