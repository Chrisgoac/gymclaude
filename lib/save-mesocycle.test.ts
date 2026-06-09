import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { guardarMesociclo } from '@/lib/save-mesocycle';
import { createExercise } from '@/lib/repositories/exercises';
import { listRoutinesByMesocycle, listRoutineExercises } from '@/lib/repositories/routines';
import { getMesocycle } from '@/lib/repositories/mesocycles';
import type { PropuestaMesociclo } from '@/lib/meso-prompt';

const propuesta: PropuestaMesociclo = {
  nombre: 'Hipertrofia 6 sem', objetivo: 'hipertrofia', semanas: 6, diasPorSemana: 2,
  notas: 'enfoque pecho', progresion: [{ semana: 1, descarga: false, ajuste: '3x10' }],
  dias: [
    { nombre: 'Push', orden: 0, ejercicios: [
      { nombre: 'Press Banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', seriesObjetivo: 4, repsObjetivo: 8, descansoSegundos: 120, nuevo: false },
    ] },
    { nombre: 'Pull', orden: 1, ejercicios: [
      { nombre: 'Remo Inventado', grupoMuscular: 'espalda', equipamiento: 'barra', tipo: 'compuesto', seriesObjetivo: 4, repsObjetivo: 10, descansoSegundos: 90, nuevo: true },
    ] },
  ],
};

beforeEach(async () => {
  await Promise.all([db.mesocycles.clear(), db.routines.clear(), db.routineExercises.clear(), db.exercises.clear()]);
});

describe('guardarMesociclo', () => {
  it('crea el mesociclo, las rutinas etiquetadas y mapea/crea ejercicios', async () => {
    // un ejercicio ya existente que debe reutilizarse por nombre normalizado
    await createExercise({ nombre: 'Press banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto' });
    const exAntes = (await db.exercises.toArray()).length;

    const id = await guardarMesociclo(propuesta);

    const meso = await getMesocycle(id);
    expect(meso?.nombre).toBe('Hipertrofia 6 sem');
    expect(meso?.notas).toBe('enfoque pecho');

    const rutinas = await listRoutinesByMesocycle(id);
    expect(rutinas.map((r) => r.nombre)).toEqual(['Push', 'Pull']);

    // 'Press Banca' reutiliza el existente; 'Remo Inventado' se crea → +1 ejercicio
    expect((await db.exercises.toArray()).length).toBe(exAntes + 1);

    const push = rutinas.find((r) => r.nombre === 'Push')!;
    const ejs = await listRoutineExercises(push.id);
    expect(ejs).toHaveLength(1);
    expect(ejs[0].seriesObjetivo).toBe(4);
    expect(ejs[0].repsObjetivo).toBe(8);
  });

  it('aplica fallback en uniones inválidas al crear un ejercicio', async () => {
    const id = await guardarMesociclo({
      ...propuesta, dias: [{ nombre: 'X', orden: 0, ejercicios: [
        { nombre: 'Cosa Rara', grupoMuscular: 'inventado', equipamiento: 'xxx', tipo: 'yyy', seriesObjetivo: 3, repsObjetivo: 12, descansoSegundos: 60, nuevo: true },
      ] }],
    });
    const rutinas = await listRoutinesByMesocycle(id);
    const ejs = await listRoutineExercises(rutinas[0].id);
    const ex = await db.exercises.get(ejs[0].exerciseId);
    expect(ex?.grupoMuscular).toBe('otro');
    expect(ex?.equipamiento).toBe('otro');
    expect(ex?.tipo).toBe('compuesto');
  });
});
