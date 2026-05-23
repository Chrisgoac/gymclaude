import { it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { startSession, addLoggedExercise, addSet } from '@/lib/repositories/workouts';
import { SessionSummaryList } from '@/components/session-summary-list';

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
});

it('muestra aviso cuando no hay entrenos', async () => {
  render(<SessionSummaryList />);
  expect(await screen.findByText('Aún no has registrado entrenos.')).toBeInTheDocument();
});

it('lista los entrenos con su volumen', async () => {
  const s = await startSession({});
  const le = await addLoggedExercise(s.id, 'seed-press-banca');
  await addSet(le.id, { peso: 60, reps: 10 });
  render(<SessionSummaryList />);
  expect(await screen.findByText(/600/)).toBeInTheDocument();
});
