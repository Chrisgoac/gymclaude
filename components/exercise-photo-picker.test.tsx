import { it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { ExercisePhotoPicker } from '@/components/exercise-photo-picker';

beforeEach(async () => {
  await db.exercisePhotos.clear();
});

it('muestra placeholder cuando no hay foto y el botón de añadir', async () => {
  render(<ExercisePhotoPicker exerciseId="e1" />);
  expect(await screen.findByText(/Añadir foto/i)).toBeInTheDocument();
});

it('muestra la foto existente y el botón de quitar', async () => {
  await db.exercisePhotos.put({
    id: 'p1', userId: null, exerciseId: 'e1', url: 'https://pub.r2.dev/x.jpg', key: 'u/e1/x.jpg',
    updatedAt: Date.now(), deletedAt: null,
  });
  render(<ExercisePhotoPicker exerciseId="e1" />);
  const img = await screen.findByRole('img');
  expect(img).toHaveAttribute('src', 'https://pub.r2.dev/x.jpg');
  expect(screen.getByText(/Quitar/i)).toBeInTheDocument();
});
