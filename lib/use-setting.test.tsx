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
});
