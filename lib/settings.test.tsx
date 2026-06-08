import { describe, it, expect, beforeEach } from 'vitest';
import 'fake-indexeddb/auto';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { useIncrementos } from '@/lib/settings';

beforeEach(async () => {
  await db.userSettings.clear();
});

function Probe() {
  const [incr, set] = useIncrementos();
  return (
    <div>
      <span data-testid="barra">{incr.barra}</span>
      <span data-testid="maquina">{incr.maquina}</span>
      <button onClick={() => set({ barra: 99 })}>b</button>
      <button onClick={() => set({ maquina: 88 })}>m</button>
    </div>
  );
}

describe('useIncrementos merge sin perder ediciones', () => {
  it('escrituras parciales sucesivas se acumulan', async () => {
    render(<Probe />);
    await userEvent.click(screen.getByRole('button', { name: 'b' }));
    await waitFor(() => expect(screen.getByTestId('barra').textContent).toBe('99'));
    await userEvent.click(screen.getByRole('button', { name: 'm' }));
    await waitFor(() => expect(screen.getByTestId('maquina').textContent).toBe('88'));
    // la primera edición NO se perdió:
    expect(screen.getByTestId('barra').textContent).toBe('99');
  });
});
