import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ProgressPhoto } from '@/lib/db/types';

const deletePhoto = vi.fn().mockResolvedValue('u1/progress/x.jpg');
vi.mock('@/lib/repositories/progress-photos', () => ({ deletePhoto: (...a: unknown[]) => deletePhoto(...a) }));

import { ProgressGallery } from '@/components/progress-gallery';

const foto = (id: string, angulo: ProgressPhoto['angulo'], fecha: number): ProgressPhoto => ({
  id, userId: null, url: `https://r2/${id}.jpg`, key: `u1/progress/${id}.jpg`, fecha, angulo, nota: null, updatedAt: fecha, deletedAt: null,
});

beforeEach(() => {
  deletePhoto.mockClear();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ok: true }) }));
});

it('agrupa por ángulo: solo muestra secciones con datos', () => {
  render(<ProgressGallery fotos={[foto('a', 'frente', 1000), foto('b', 'lado', 2000)]} />);
  expect(screen.getByText('Frente')).toBeInTheDocument();
  expect(screen.getByText('Lado')).toBeInTheDocument();
  expect(screen.queryByText('Espalda')).not.toBeInTheDocument();
});

it('la miniatura está difuminada por defecto y se revela al tocar', async () => {
  render(<ProgressGallery fotos={[foto('a', 'frente', 1000)]} />);
  const img = screen.getByAltText(/frente/i);
  expect(img.className).toContain('blur');
  await userEvent.click(img);
  expect(img.className).not.toContain('blur');
});

it('borrar llama a deletePhoto con el id', async () => {
  render(<ProgressGallery fotos={[foto('a', 'frente', 1000)]} />);
  await userEvent.click(screen.getByRole('button', { name: /eliminar/i }));
  expect(deletePhoto).toHaveBeenCalledWith('a');
});
