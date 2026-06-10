import { it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { Achievement } from '@/lib/db/types';
import { LogrosView } from '@/components/logros-view';

const ach = (clave: string, fecha: number): Achievement => ({
  id: clave, userId: null, clave, fechaDesbloqueo: fecha, updatedAt: fecha, deletedAt: null,
});

it('muestra la racha actual y la mejor', () => {
  render(<LogrosView racha={{ actual: 3, mejor: 7 }} achievements={new Map()} prs={[]} />);
  expect(screen.getByText(/racha actual/i)).toBeInTheDocument();
  expect(screen.getByText('3')).toBeInTheDocument();
  expect(screen.getByText(/7/)).toBeInTheDocument();
});

it('hito desbloqueado muestra título; bloqueado muestra criterio en gris', () => {
  const map = new Map<string, Achievement>([['sesiones-10', ach('sesiones-10', 1700000000000)]]);
  render(<LogrosView racha={{ actual: 0, mejor: 0 }} achievements={map} prs={[]} />);
  // desbloqueado
  expect(screen.getByText('Calentando')).toBeInTheDocument();
  // bloqueado: la descripción de un hito no desbloqueado aparece
  expect(screen.getByText('250 entrenos')).toBeInTheDocument();
});

it('galería de PRs lista ejercicio + peso', () => {
  render(
    <LogrosView
      racha={{ actual: 0, mejor: 0 }}
      achievements={new Map()}
      prs={[{ exerciseId: 'e1', nombre: 'Press banca', peso: 100, fecha: 1700000000000 }]}
    />,
  );
  expect(screen.getByText('Press banca')).toBeInTheDocument();
  expect(screen.getByText(/100 kg/)).toBeInTheDocument();
});
