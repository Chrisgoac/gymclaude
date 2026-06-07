import { it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createRoutine, listRoutines } from '@/lib/repositories/routines';
import { RoutineOrderManager } from '@/components/routine-order-manager';

beforeEach(async () => {
  await db.routines.clear();
});

it('baja una rutina y persiste el nuevo orden', async () => {
  await createRoutine({ nombre: 'A' });
  await createRoutine({ nombre: 'B' });
  render(<RoutineOrderManager />);
  await screen.findByText('A');
  await userEvent.click(screen.getByRole('button', { name: 'Bajar A' }));
  await waitFor(async () => {
    expect((await listRoutines()).map((r) => r.nombre)).toEqual(['B', 'A']);
  });
});
