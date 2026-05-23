import { describe, it, expect, beforeEach, vi } from 'vitest';
import { Suspense } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createRoutine, listDays } from '@/lib/repositories/routines';
import RoutineEditorPage from '@/app/rutinas/[id]/page';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

beforeEach(async () => {
  await db.routines.clear();
  await db.routineDays.clear();
  await db.routineExercises.clear();
});

it('añade un día a la rutina', async () => {
  const r = await createRoutine({ nombre: 'R' });
  render(
    <Suspense>
      <RoutineEditorPage params={Promise.resolve({ id: r.id })} />
    </Suspense>,
  );
  expect(await screen.findByText('R')).toBeInTheDocument();
  await userEvent.type(screen.getByPlaceholderText('Nombre del día'), 'Empuje');
  await userEvent.click(screen.getByRole('button', { name: 'Añadir día' }));
  expect(await screen.findByText('Empuje')).toBeInTheDocument();
  expect(await listDays(r.id)).toHaveLength(1);
});
