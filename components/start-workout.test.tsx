import { it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createGym } from '@/lib/repositories/gyms';
import { StartWorkout } from '@/components/start-workout';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.routines.clear();
  await db.routineDays.clear();
  await db.gyms.clear();
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
  expect(push).toHaveBeenCalledWith(`/entrenar/${sesion.id}`);
});
