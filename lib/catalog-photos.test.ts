import { describe, it, expect } from 'vitest';
import { resolveExercisePhotoUrl, CATALOG_PHOTOS } from '@/lib/catalog-photos';

describe('catalog-photos', () => {
  it('la foto del usuario tiene prioridad', () => {
    expect(resolveExercisePhotoUrl('seed-press-banca', 'https://r2/mia.jpg')).toBe('https://r2/mia.jpg');
  });

  it('sin foto del usuario, devuelve el render por defecto del catálogo', () => {
    expect(resolveExercisePhotoUrl('seed-press-banca')).toBe('/catalog/press-banca.jpg');
  });

  it('sin foto ni default (ejercicio propio), devuelve undefined', () => {
    expect(resolveExercisePhotoUrl('id-aleatorio')).toBeUndefined();
  });

  it('el mapa contiene rutas /catalog/*.jpg', () => {
    expect(Object.values(CATALOG_PHOTOS).every((u) => u.startsWith('/catalog/') && u.endsWith('.jpg'))).toBe(true);
    expect(CATALOG_PHOTOS['seed-press-banca']).toBe('/catalog/press-banca.jpg');
  });
});
