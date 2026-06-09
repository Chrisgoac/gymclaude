import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const addMetric = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/repositories/body', () => ({ addMetric: (...a: unknown[]) => addMetric(...a) }));

const addMetricaPersonalizada = vi.fn().mockResolvedValue({ clave: 'grasa', label: '% Grasa', unidad: '%' });
const useSettingMock = vi.fn();
vi.mock('@/lib/body-metrics', async (orig) => {
  const real = await orig<typeof import('@/lib/body-metrics')>();
  return { ...real, addMetricaPersonalizada: (...a: unknown[]) => addMetricaPersonalizada(...a) };
});
vi.mock('@/lib/use-setting', () => ({ useSetting: (...a: unknown[]) => useSettingMock(...a) }));

import { BodyForm } from '@/components/body-form';

beforeEach(() => {
  addMetric.mockClear();
  addMetricaPersonalizada.mockClear();
  useSettingMock.mockReturnValue([[], vi.fn()]); // sin personalizadas por defecto
});

it('registrar una entrada llama a addMetric con tipo y valor', async () => {
  render(<BodyForm />);
  await userEvent.selectOptions(screen.getByLabelText(/métrica/i), 'cintura');
  await userEvent.type(screen.getByLabelText(/valor/i), '84,5');
  await userEvent.click(screen.getByRole('button', { name: /registrar/i }));
  expect(addMetric).toHaveBeenCalledTimes(1);
  const [tipo, valor] = addMetric.mock.calls[0];
  expect(tipo).toBe('cintura');
  expect(valor).toBe(84.5); // coma convertida a punto
});

it('las personalizadas aparecen en el selector', () => {
  useSettingMock.mockReturnValue([[{ clave: 'grasa', label: '% Grasa', unidad: '%' }], vi.fn()]);
  render(<BodyForm />);
  expect(screen.getByRole('option', { name: /% Grasa/ })).toBeInTheDocument();
});

it('gestionar métricas crea una personalizada', async () => {
  render(<BodyForm />);
  await userEvent.click(screen.getByRole('button', { name: /gestionar métricas/i }));
  await userEvent.type(screen.getByLabelText(/nombre/i), 'Grasa');
  await userEvent.type(screen.getByLabelText(/unidad/i), '%');
  await userEvent.click(screen.getByRole('button', { name: /añadir métrica/i }));
  expect(addMetricaPersonalizada).toHaveBeenCalledWith('Grasa', '%');
});
