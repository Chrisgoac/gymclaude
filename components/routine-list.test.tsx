import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { createRoutine } from '@/lib/repositories/routines';
import { RoutineList } from '@/components/routine-list';

describe('RoutineList', () => {
  beforeEach(async () => {
    await db.routines.clear();
  });

  it('muestra el aviso cuando no hay rutinas', async () => {
    render(<RoutineList />);
    expect(await screen.findByText('Aún no tienes rutinas.')).toBeInTheDocument();
  });

  it('lista las rutinas existentes', async () => {
    await createRoutine({ nombre: 'Full Body' });
    await createRoutine({ nombre: 'Push Pull Legs' });
    render(<RoutineList />);
    expect(await screen.findByText('Full Body')).toBeInTheDocument();
    expect(screen.getByText('Push Pull Legs')).toBeInTheDocument();
  });
});
