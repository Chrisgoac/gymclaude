import { it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { startSession, addLoggedExercise, addSet } from '@/lib/repositories/workouts';
import { ExerciseProgress } from '@/components/exercise-progress';

beforeEach(async () => {
  await db.workoutSessions.clear();
  await db.loggedExercises.clear();
  await db.loggedSets.clear();
});

it('muestra los récords personales del ejercicio', async () => {
  const s = await startSession({});
  const le = await addLoggedExercise(s.id, 'seed-press-banca');
  await addSet(le.id, { peso: 80, reps: 1 });
  render(<ExerciseProgress exerciseId="seed-press-banca" />);
  expect(await screen.findByText('Máx. peso')).toBeInTheDocument();
  // findAllByText used because both maxPeso and mejor1RM equal 80 when reps=1
  const matches = await screen.findAllByText(/80/);
  expect(matches.length).toBeGreaterThan(0);
});

it('avisa cuando no hay datos del ejercicio', async () => {
  render(<ExerciseProgress exerciseId="seed-press-banca" />);
  expect(await screen.findByText('Sin datos todavía para este ejercicio.')).toBeInTheDocument();
});
