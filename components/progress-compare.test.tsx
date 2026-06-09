import { it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ProgressPhoto } from '@/lib/db/types';
import { ProgressCompare } from '@/components/progress-compare';

const foto = (id: string, angulo: ProgressPhoto['angulo'], fecha: number): ProgressPhoto => ({
  id, userId: null, url: `https://r2/${id}.jpg`, key: `u1/progress/${id}.jpg`, fecha, angulo, nota: null, updatedAt: fecha, deletedAt: null,
});

it('no renderiza nada si ningún ángulo tiene 2+ fotos', () => {
  const { container } = render(<ProgressCompare fotos={[foto('a', 'frente', 1000), foto('b', 'lado', 2000)]} />);
  expect(container).toBeEmptyDOMElement();
});

it('con 2+ fotos de un ángulo muestra dos imágenes (A=más antigua, B=más reciente)', () => {
  render(<ProgressCompare fotos={[foto('viejo', 'frente', 1000), foto('nuevo', 'frente', 3000)]} />);
  const imgs = screen.getAllByRole('img');
  expect(imgs).toHaveLength(2);
  expect((imgs[0] as HTMLImageElement).src).toContain('viejo'); // A = más antigua
  expect((imgs[1] as HTMLImageElement).src).toContain('nuevo'); // B = más reciente
});

it('cambiar el selector A cambia la imagen mostrada', async () => {
  render(<ProgressCompare fotos={[
    foto('f1', 'frente', 1000), foto('f2', 'frente', 2000), foto('f3', 'frente', 3000),
  ]} />);
  const selA = screen.getByLabelText('Foto A');
  await userEvent.selectOptions(selA, 'f2');
  const imgs = screen.getAllByRole('img') as HTMLImageElement[];
  expect(imgs[0].src).toContain('f2');
});
