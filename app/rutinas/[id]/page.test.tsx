import { it, expect, beforeEach, vi } from 'vitest';
import { Suspense } from 'react';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { createRoutine, addExerciseToRoutine } from '@/lib/repositories/routines';
import RoutineEditorPage from '@/app/rutinas/[id]/page';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

beforeEach(async () => {
  await db.routines.clear();
  await db.routineExercises.clear();
  await db.exercises.clear();
  await db.exercises.put({
    id: 'seed-press-banca', userId: null, nombre: 'Press de banca', grupoMuscular: 'pecho',
    equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 0, deletedAt: null,
  });
});

it('muestra los ejercicios de la rutina', async () => {
  const r = await createRoutine({ nombre: 'R' });
  await addExerciseToRoutine(r.id, { exerciseId: 'seed-press-banca' });
  render(
    <Suspense>
      <RoutineEditorPage params={Promise.resolve({ id: r.id })} />
    </Suspense>,
  );
  expect(await screen.findByText('R')).toBeInTheDocument();
  expect(await screen.findByText('Press de banca')).toBeInTheDocument();
});
