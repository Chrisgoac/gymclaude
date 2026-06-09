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
  it('marca un grupo descuidado con "hace N días"', () => {
    const ahora = 100 * 86400000; // día 100
    render(
      <MuscleBalance
        data={[{ grupo: 'pecho', volumen: 500 }]}
        lastTrained={{ pecho: 80 * 86400000 /* hace 20 días */ } as unknown as Record<import('@/lib/db/types').MuscleGroup, number | null>}
        ahora={ahora}
      />,
    );
    expect(screen.getByText(/hace 20d/)).toBeInTheDocument();
  });
  it('muestra el % de meta de volumen por grupo', () => {
    render(
      <MuscleBalance
        data={[{ grupo: 'pecho', volumen: 1600 }]}
        objetivos={{ pecho: 400 } as Partial<Record<import('@/lib/db/types').MuscleGroup, number>>}
        volumenSemana={{ pecho: 300 } as Record<import('@/lib/db/types').MuscleGroup, number>}
      />,
    );
    expect(screen.getByText(/75% meta sem\./)).toBeInTheDocument();
  });
});
