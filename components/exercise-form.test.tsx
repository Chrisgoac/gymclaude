import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { ExerciseForm } from '@/components/exercise-form';

describe('ExerciseForm (crear)', () => {
  beforeEach(async () => {
    await db.exercises.clear();
  });

  it('crea un ejercicio al enviar y llama onSaved', async () => {
    const onSaved = vi.fn();
    render(<ExerciseForm onSaved={onSaved} />);
    await userEvent.type(screen.getByLabelText('Nombre'), 'Face pull');
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    const all = await db.exercises.toArray();
    expect(all.map((e) => e.nombre)).toContain('Face pull');
  });

  it('no guarda si el nombre está vacío', async () => {
    const onSaved = vi.fn();
    render(<ExerciseForm onSaved={onSaved} />);
    await userEvent.click(screen.getByRole('button', { name: 'Guardar' }));
    expect(onSaved).not.toHaveBeenCalled();
    expect(await db.exercises.count()).toBe(0);
  });
});
