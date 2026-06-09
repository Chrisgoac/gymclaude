import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { BodyMetric } from '@/lib/db/types';

const deleteMetric = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/repositories/body', () => ({ deleteMetric: (...a: unknown[]) => deleteMetric(...a) }));
// La gráfica usa Recharts (no renderiza en jsdom): la stubeamos.
vi.mock('@/components/body-metric-chart', () => ({ BodyMetricChart: () => <div data-testid="chart" /> }));

import { BodyMetricCard } from '@/components/body-metric-card';

const bm = (id: string, valor: number, fecha: number): BodyMetric => ({
  id, userId: null, tipo: 'peso', valor, fecha, updatedAt: fecha, deletedAt: null,
});

beforeEach(() => { deleteMetric.mockClear(); });

it('muestra label, valor actual y delta', () => {
  render(<BodyMetricCard tipo="peso" def={{ label: 'Peso', unidad: 'kg' }} metrics={[bm('a', 80, 1000), bm('b', 77, 2000)]} />);
  expect(screen.getByText('Peso')).toBeInTheDocument();
  expect(screen.getAllByText(/77/).length).toBeGreaterThan(0);
  expect(screen.getByText(/-3/)).toBeInTheDocument(); // delta
});

it('el botón borrar llama a deleteMetric con el id', async () => {
  render(<BodyMetricCard tipo="peso" def={{ label: 'Peso', unidad: 'kg' }} metrics={[bm('a', 80, 1000)]} />);
  await userEvent.click(screen.getByRole('button', { name: /eliminar/i }));
  expect(deleteMetric).toHaveBeenCalledWith('a');
});
