import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Mesocycle, Routine } from '@/lib/db/types';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));

const startSession = vi.fn().mockResolvedValue({ id: 'sess-1' });
vi.mock('@/lib/repositories/workouts', () => ({ startSession: (...a: unknown[]) => startSession(...a) }));

const deleteMesocycle = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/repositories/mesocycles', async (orig) => {
  const real = await orig<typeof import('@/lib/repositories/mesocycles')>();
  return { ...real, deleteMesocycle: (...a: unknown[]) => deleteMesocycle(...a) };
});

import { MesocycleView } from '@/components/mesocycle-view';

const DIA = 86400000;
const meso: Mesocycle = {
  id: 'm1', userId: null, nombre: 'Hipertrofia', objetivo: 'hipertrofia', semanas: 6, diasPorSemana: 2,
  notas: 'foco pecho', fechaInicio: 0,
  progresion: [
    { semana: 1, descarga: false, ajuste: '3x10' },
    { semana: 2, descarga: false, ajuste: '4x10' },
  ],
  updatedAt: 1, deletedAt: null,
};
const routines: Routine[] = [
  { id: 'r1', userId: null, nombre: 'Push', orden: 0, archivada: false, mesocycleId: 'm1', updatedAt: 1, deletedAt: null },
  { id: 'r2', userId: null, nombre: 'Pull', orden: 1, archivada: false, mesocycleId: 'm1', updatedAt: 1, deletedAt: null },
];

beforeEach(() => { push.mockReset(); startSession.mockClear(); deleteMesocycle.mockClear(); });

it('resalta la semana actual (ahora = día 8 → semana 2)', () => {
  render(<MesocycleView meso={meso} routines={routines} ahora={8 * DIA} />);
  const sem2 = screen.getByText(/4x10/).closest('li')!;
  expect(sem2.className).toMatch(/bg-primary|font-bold|border/); // marca de actual
});

it('Empezar inicia sesión de la rutina y navega', async () => {
  render(<MesocycleView meso={meso} routines={routines} ahora={0} />);
  await userEvent.click(screen.getAllByRole('button', { name: /empezar/i })[0]);
  expect(startSession).toHaveBeenCalledWith({ routineId: 'r1' });
  await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/entrenar/sess-1'));
});

it('borrar el mesociclo llama deleteMesocycle y navega a /rutinas', async () => {
  render(<MesocycleView meso={meso} routines={routines} ahora={0} />);
  await userEvent.click(screen.getByRole('button', { name: /borrar mesociclo/i }));
  expect(deleteMesocycle).toHaveBeenCalledWith('m1');
  await vi.waitFor(() => expect(push).toHaveBeenCalledWith('/rutinas'));
});
