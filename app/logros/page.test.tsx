import { describe, it, expect, beforeEach, vi } from 'vitest';
import 'fake-indexeddb/auto';
import { render, waitFor } from '@testing-library/react';
import { db } from '@/lib/db/database';

const reconciliar = vi.fn(async () => []);
vi.mock('@/lib/reconciliar-logros', () => ({ reconciliarLogros: (...args: unknown[]) => reconciliar(...(args as [])) }));
vi.mock('@/components/logros-view', () => ({ LogrosView: () => null }));

import LogrosPage from '@/app/logros/page';

beforeEach(async () => {
  reconciliar.mockClear();
  await db.userSettings.clear();
});

describe('LogrosPage', () => {
  it('no reconcilia con el objetivo de reserva: espera a que el ajuste cargue', async () => {
    await db.userSettings.put({ id: 'objetivoSemanal', userId: null, valor: 5, updatedAt: 1, deletedAt: null } as never);
    render(<LogrosPage />);
    await waitFor(() => expect(reconciliar).toHaveBeenCalled());
    expect(reconciliar.mock.calls.every((c) => (c as unknown[])[0] === 5)).toBe(true);
  });

  it('sin ajuste guardado reconcilia con el valor por defecto una vez cargado', async () => {
    render(<LogrosPage />);
    await waitFor(() => expect(reconciliar).toHaveBeenCalled());
    expect((reconciliar.mock.calls[0] as unknown[])[0]).toBe(3);
  });
});
