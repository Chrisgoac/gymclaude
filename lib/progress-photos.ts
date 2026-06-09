import type { AnguloFoto } from '@/lib/db/types';

/** Ángulos en orden de presentación. */
export const ANGULOS: readonly AnguloFoto[] = ['frente', 'lado', 'espalda'];

export const anguloLabel: Record<AnguloFoto, string> = {
  frente: 'Frente',
  lado: 'Lado',
  espalda: 'Espalda',
};
