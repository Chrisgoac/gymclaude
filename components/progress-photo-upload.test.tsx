import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const addPhoto = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/repositories/progress-photos', () => ({ addPhoto: (...a: unknown[]) => addPhoto(...a) }));
vi.mock('@/lib/image/compress', () => ({ compressImage: vi.fn().mockResolvedValue(new Blob([new Uint8Array([1])], { type: 'image/jpeg' })) }));

import { ProgressPhotoUpload } from '@/components/progress-photo-upload';

beforeEach(() => {
  addPhoto.mockClear();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ url: 'https://r2/x.jpg', key: 'u1/progress/x.jpg' }) }));
  // online por defecto
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
});

function fakeFile() {
  return new File([new Uint8Array([1, 2, 3])], 'foto.jpg', { type: 'image/jpeg' });
}

it('al subir: comprime, postea y guarda la entidad con angulo/nota', async () => {
  render(<ProgressPhotoUpload />);
  await userEvent.selectOptions(screen.getByLabelText(/ángulo/i), 'lado');
  await userEvent.type(screen.getByLabelText(/nota/i), 'semana 1');
  const input = screen.getByLabelText(/foto/i, { selector: 'input[type="file"]' });
  await userEvent.upload(input, fakeFile());
  // espera microtareas del onFile async
  expect(fetch).toHaveBeenCalledWith('/api/progress-photos', expect.objectContaining({ method: 'POST' }));
  expect(addPhoto).toHaveBeenCalledTimes(1);
  const arg = addPhoto.mock.calls[0][0];
  expect(arg).toMatchObject({ url: 'https://r2/x.jpg', key: 'u1/progress/x.jpg', angulo: 'lado', nota: 'semana 1' });
  expect(typeof arg.fecha).toBe('number');
});

it('si la subida falla (res no ok), no guarda la entidad y muestra error', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503, json: async () => ({}) }));
  render(<ProgressPhotoUpload />);
  const input = screen.getByLabelText(/foto/i, { selector: 'input[type="file"]' });
  await userEvent.upload(input, fakeFile());
  expect(addPhoto).not.toHaveBeenCalled();
  expect(screen.getByText(/no se pudo subir/i)).toBeInTheDocument();
});
