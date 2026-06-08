import { it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { startSession, addLoggedExercise, listExerciseSets, addSet } from '@/lib/repositories/workouts';
import { LoggedExerciseCard } from '@/components/logged-exercise-card';

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
  await db.exercises.clear();
  await db.exercises.put({
    id: 'seed-press-banca', userId: null, nombre: 'Press de banca', grupoMuscular: 'pecho',
    equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 0, deletedAt: null,
  });
});

it('muestra el ejercicio y añade una serie con peso y reps', async () => {
  const s = await startSession({});
  const le = await addLoggedExercise(s.id, 'seed-press-banca');

  render(<LoggedExerciseCard loggedExercise={le} sessionId={s.id} />);
  expect(await screen.findByText('Press de banca')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: 'Añadir serie' }));
  const pesos = await screen.findAllByLabelText('Peso');
  await userEvent.clear(pesos[0]);
  await userEvent.type(pesos[0], '60');
  const reps = screen.getAllByLabelText('Reps');
  await userEvent.clear(reps[0]);
  await userEvent.type(reps[0], '8');

  await waitFor(async () => {
    const sets = await listExerciseSets(le.id);
    expect(sets).toHaveLength(1);
    expect(sets[0]).toMatchObject({ peso: 60, reps: 8 });
  });
});

it('muestra "Última vez" con el peso y reps del entreno anterior', async () => {
  const vieja = await startSession({});
  const leVieja = await addLoggedExercise(vieja.id, 'seed-press-banca');
  await addSet(leVieja.id, { peso: 70, reps: 8 });
  await new Promise((res) => setTimeout(res, 3));

  const s = await startSession({});
  const le = await addLoggedExercise(s.id, 'seed-press-banca');
  render(<LoggedExerciseCard loggedExercise={le} sessionId={s.id} />);

  expect(await screen.findByText(/ÚLTIMA VEZ/i)).toBeInTheDocument();
  expect(await screen.findByText(/70/)).toBeInTheDocument();
});
