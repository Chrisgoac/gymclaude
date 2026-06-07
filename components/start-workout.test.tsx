import { it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createGym } from '@/lib/repositories/gyms';
import { createRoutine, addExerciseToRoutine } from '@/lib/repositories/routines';
import { startSession } from '@/lib/repositories/workouts';
import { setSuggestNextRoutine } from '@/lib/settings';
import { StartWorkout } from '@/components/start-workout';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.routines.clear();
  await db.routineExercises.clear();
  await db.gyms.clear();
  localStorage.clear();
  push.mockClear();
});

it('elige gimnasio, empieza un entreno libre y navega a la sesión', async () => {
  const g = await createGym("Gold's");
  render(<StartWorkout />);
  await userEvent.click(screen.getByRole('button', { name: 'Empezar entreno libre' }));
  await userEvent.click(await screen.findByRole('button', { name: "Gold's" }));
  await waitFor(() => expect(push).toHaveBeenCalled());
  expect(await db.workoutSessions.count()).toBe(1);
  const sesion = (await db.workoutSessions.toArray())[0];
  expect(sesion.gymId).toBe(g.id);
});

it('empieza desde una rutina precargando sus ejercicios', async () => {
  await createGym("Gold's");
  const r = await createRoutine({ nombre: 'Full Body' });
  await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
  render(<StartWorkout />);
  await userEvent.click(await screen.findByRole('button', { name: 'Empezar Full Body' }));
  await userEvent.click(await screen.findByRole('button', { name: "Gold's" }));
  await waitFor(() => expect(push).toHaveBeenCalled());
  const sesion = (await db.workoutSessions.toArray())[0];
  const les = await db.loggedExercises.where('sessionId').equals(sesion.id).toArray();
  expect(les).toHaveLength(1);
});

it('con el toggle activo muestra la tarjeta Siguiente y la última hecha', async () => {
  const a = await createRoutine({ nombre: 'Empuje' });
  await createRoutine({ nombre: 'Tirón' });
  await startSession({ routineId: a.id }); // última = Empuje → siguiente = Tirón
  setSuggestNextRoutine(true);
  render(<StartWorkout />);
  await screen.findByText('Siguiente');
  expect(screen.getByRole('heading', { name: 'Tirón' })).toBeInTheDocument();
  expect(screen.getByText(/Última: Empuje/)).toBeInTheDocument();
});

it('con el toggle desactivado no muestra tarjeta, solo la última', async () => {
  const a = await createRoutine({ nombre: 'Empuje' });
  await createRoutine({ nombre: 'Tirón' });
  await startSession({ routineId: a.id });
  render(<StartWorkout />);
  await screen.findByText(/Última: Empuje/);
  expect(screen.queryByText('Siguiente')).not.toBeInTheDocument();
});

it('sin entrenos desde rutina no muestra ni tarjeta ni última', async () => {
  await createRoutine({ nombre: 'Empuje' });
  setSuggestNextRoutine(true);
  render(<StartWorkout />);
  await screen.findByRole('button', { name: 'Empezar entreno libre' });
  expect(screen.queryByText('Siguiente')).not.toBeInTheDocument();
  expect(screen.queryByText(/Última:/)).not.toBeInTheDocument();
});
