import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createRoutine, addDay, addExerciseToDay } from '@/lib/repositories/routines';
import { RoutineDayExerciseRow } from '@/components/routine-day-exercise-row';

beforeEach(async () => {
  await db.routines.clear();
  await db.routineDays.clear();
  await db.routineExercises.clear();
  await db.exercises.clear();
  await db.exercises.put({
    id: 'seed-press-banca', userId: null, nombre: 'Press de banca', grupoMuscular: 'pecho',
    equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 0, deletedAt: null,
  });
});

it('muestra el nombre del ejercicio y guarda los objetivos', async () => {
  const r = await createRoutine({ nombre: 'R' });
  const d = await addDay(r.id, { nombre: 'D' });
  const re = await addExerciseToDay(d.id, { exerciseId: 'seed-press-banca' });

  render(<RoutineDayExerciseRow routineExercise={re} />);
  expect(await screen.findByText('Press de banca')).toBeInTheDocument();

  await userEvent.type(screen.getByLabelText('Series'), '3');
  await userEvent.type(screen.getByLabelText('Reps'), '8');
  await waitFor(async () => {
    const stored = await db.routineExercises.get(re.id);
    expect(stored?.seriesObjetivo).toBe(3);
    expect(stored?.repsObjetivo).toBe(8);
  });
});
