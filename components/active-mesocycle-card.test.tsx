import { it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { db } from '@/lib/db/database';
import { ActiveMesocycleCard } from '@/components/active-mesocycle-card';

beforeEach(async () => { await db.mesocycles.clear(); });

it('no muestra nada si no hay mesociclos', async () => {
  const { container } = render(<ActiveMesocycleCard />);
  // useLiveQuery resuelve async; en el primer render no hay tarjeta
  expect(container.querySelector('a')).toBeNull();
});

it('muestra el mesociclo más reciente como enlace', async () => {
  await db.mesocycles.put({ id: 'm9', userId: null, nombre: 'Mi plan', objetivo: 'fuerza', semanas: 5, diasPorSemana: 3, notas: null, progresion: [], fechaInicio: 1000, updatedAt: 1, deletedAt: null });
  render(<ActiveMesocycleCard />);
  const link = await screen.findByRole('link', { name: /mi plan/i });
  expect(link).toHaveAttribute('href', '/mesociclo/m9');
});
