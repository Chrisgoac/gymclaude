import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));

const recogerSnapshot = vi.fn();
vi.mock('@/lib/coach-snapshot', () => ({ recogerSnapshot: (...a: unknown[]) => recogerSnapshot(...a) }));

const listExercises = vi.fn();
vi.mock('@/lib/repositories/exercises', () => ({ listExercises: (...a: unknown[]) => listExercises(...a) }));

const guardarMesociclo = vi.fn();
vi.mock('@/lib/save-mesocycle', () => ({ guardarMesociclo: (...a: unknown[]) => guardarMesociclo(...a) }));

import { MesoGenerator } from '@/components/meso-generator';

const PROPUESTA = {
  nombre: 'Plan IA', objetivo: 'hipertrofia', semanas: 6, diasPorSemana: 2,
  notas: 'x', progresion: [{ semana: 1, descarga: false, ajuste: '3x10' }],
  dias: [{ nombre: 'Push', orden: 0, ejercicios: [
    { nombre: 'Press Banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', seriesObjetivo: 4, repsObjetivo: 8, descansoSegundos: 120, nuevo: true },
  ] }],
};

beforeEach(() => {
  push.mockReset();
  recogerSnapshot.mockReset().mockResolvedValue({ estancados: [], semana: {}, grupos: [], cuerpo: { peso: null, medidas: [] } });
  listExercises.mockReset().mockResolvedValue([{ id: 'e1', nombre: 'Sentadilla', grupoMuscular: 'cuadriceps', equipamiento: 'barra', deletedAt: null }]);
  guardarMesociclo.mockReset().mockResolvedValue('meso-9');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => PROPUESTA }));
});

it('generar: postea params+snapshot+catalogo y pinta la revisión', async () => {
  render(<MesoGenerator />);
  await userEvent.click(screen.getByRole('button', { name: /generar/i }));
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/coach/mesociclo', expect.objectContaining({ method: 'POST' })));
  const body = JSON.parse((fetch as unknown as { mock: { calls: [string, { body: string }][] } }).mock.calls[0][1].body);
  expect(body.params.objetivo).toBeTruthy();
  expect(body.catalogo[0].nombre).toBe('Sentadilla');
  await waitFor(() => expect(screen.getByText('Plan IA')).toBeInTheDocument());
  expect(screen.getByText('Push')).toBeInTheDocument();
  expect(screen.getByText(/Press Banca/)).toBeInTheDocument();
});

it('guardar: llama guardarMesociclo y navega a /mesociclo/:id', async () => {
  render(<MesoGenerator />);
  await userEvent.click(screen.getByRole('button', { name: /generar/i }));
  await waitFor(() => expect(screen.getByText('Plan IA')).toBeInTheDocument());
  await userEvent.click(screen.getByRole('button', { name: /guardar mesociclo/i }));
  await waitFor(() => expect(guardarMesociclo).toHaveBeenCalledWith(PROPUESTA));
  expect(push).toHaveBeenCalledWith('/mesociclo/meso-9');
});

it('muestra error si la generación falla', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }));
  render(<MesoGenerator />);
  await userEvent.click(screen.getByRole('button', { name: /generar/i }));
  await waitFor(() => expect(screen.getByText(/no se pudo generar|no disponible/i)).toBeInTheDocument());
  expect(guardarMesociclo).not.toHaveBeenCalled();
});
