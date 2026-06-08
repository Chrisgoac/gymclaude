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
  await db.routineExercises.clear();
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

it('autorrellena la primera serie con la sugerencia y muestra el badge ▲ +5 kg', async () => {
  await db.exercises.put({
    id: 'ex-1', userId: null, nombre: 'Press', grupoMuscular: 'pecho',
    equipamiento: 'maquina', tipo: 'compuesto', esPersonalizado: false,
    updatedAt: 1, deletedAt: null,
  });
  const prev = await startSession({ routineId: 'r1', gymId: 'g1' });
  const lePrev = await addLoggedExercise(prev.id, 'ex-1');
  await addSet(lePrev.id, { peso: 40, reps: 12 });
  await addSet(lePrev.id, { peso: 40, reps: 12 });
  await db.routineExercises.put({
    id: 're-1', routineId: 'r1', exerciseId: 'ex-1', orden: 0,
    seriesObjetivo: 3, repsObjetivo: 12, updatedAt: 1, deletedAt: null,
  });

  const sesion = await startSession({ routineId: 'r1', gymId: 'g1' });
  const le = await addLoggedExercise(sesion.id, 'ex-1');

  render(<LoggedExerciseCard loggedExercise={le} sessionId={sesion.id} gymId="g1" routineId="r1" />);

  expect(await screen.findByText('▲ +5 kg')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Añadir serie' }));
  const peso = await screen.findByLabelText('Peso');
  expect((peso as HTMLInputElement).value).toBe('45');
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
