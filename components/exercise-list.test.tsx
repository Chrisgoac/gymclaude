import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { createExercise } from '@/lib/repositories/exercises';
import { ExerciseList } from '@/components/exercise-list';

describe('ExerciseList', () => {
  beforeEach(async () => {
    await db.exercises.clear();
    await createExercise({ nombre: 'Press de banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto' });
    await createExercise({ nombre: 'Curl martillo', grupoMuscular: 'biceps', equipamiento: 'mancuerna', tipo: 'aislamiento' });
  });

  it('muestra los ejercicios y sus grupos musculares', async () => {
    render(<ExerciseList />);
    expect(await screen.findByText('Press de banca')).toBeInTheDocument();
    expect(screen.getByText('Curl martillo')).toBeInTheDocument();
    expect(screen.getByText('Pecho')).toBeInTheDocument();
    expect(screen.getByText('Bíceps')).toBeInTheDocument();
  });

  it('filtra por el texto de búsqueda', async () => {
    render(<ExerciseList />);
    await screen.findByText('Press de banca');
    await userEvent.type(screen.getByPlaceholderText('Buscar ejercicio…'), 'curl');
    await waitFor(() => {
      expect(screen.queryByText('Press de banca')).not.toBeInTheDocument();
      expect(screen.getByText('Curl martillo')).toBeInTheDocument();
    });
  });

  it('filtra por chip de grupo muscular', async () => {
    render(<ExerciseList />);
    await screen.findByText('Press de banca');
    await userEvent.click(screen.getByRole('button', { name: 'Bíceps' }));
    await waitFor(() => {
      expect(screen.queryByText('Press de banca')).not.toBeInTheDocument();
      expect(screen.getByText('Curl martillo')).toBeInTheDocument();
    });
  });
});
