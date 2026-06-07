import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MuscleBalance } from '@/components/muscle-balance';

describe('MuscleBalance', () => {
  it('muestra etiqueta y valor de cada grupo', () => {
    render(<MuscleBalance data={[
      { grupo: 'pecho', volumen: 1000 },
      { grupo: 'biceps', volumen: 250 },
    ]} />);
    expect(screen.getByText('Pecho')).toBeInTheDocument();
    expect(screen.getByText('Bíceps')).toBeInTheDocument();
    expect(screen.getByText('1000 kg·rep')).toBeInTheDocument();
  });
  it('muestra aviso cuando no hay datos', () => {
    render(<MuscleBalance data={[]} />);
    expect(screen.getByText(/Aún no hay volumen/i)).toBeInTheDocument();
  });
});
