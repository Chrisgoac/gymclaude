import { describe, it, expect, beforeEach } from 'vitest';
import 'fake-indexeddb/auto';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { useSetting } from '@/lib/use-setting';

beforeEach(async () => {
  await db.userSettings.clear();
});

function Probe() {
  const [valor, set] = useSetting<number>('objetivoSemanal', 3);
  return (
    <div>
      <span data-testid="valor">{valor}</span>
      <button onClick={() => set(5)}>set5</button>
    </div>
  );
}

describe('useSetting', () => {
  it('devuelve el fallback cuando no hay valor y persiste/reacciona al cambiar', async () => {
    render(<Probe />);
    expect(screen.getByTestId('valor').textContent).toBe('3');
    await userEvent.click(screen.getByRole('button', { name: 'set5' }));
    await waitFor(() => expect(screen.getByTestId('valor').textContent).toBe('5'));
  });

  it('loaded es false mientras lee y true despues, tambien si el ajuste no existe', async () => {
    function Loaded() {
      const [valor, , loaded] = useSetting<number>('objetivoSemanal', 3);
      return <span data-testid="estado">{loaded ? `cargado:${valor}` : 'cargando'}</span>;
    }
    await db.userSettings.put({ id: 'objetivoSemanal', userId: null, valor: 7, updatedAt: 1, deletedAt: null } as never);
    render(<Loaded />);
    expect(screen.getByTestId('estado').textContent).toBe('cargando');
    await waitFor(() => expect(screen.getByTestId('estado').textContent).toBe('cargado:7'));
  });
});
