import { it, expect, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { db } from '@/lib/db/database';
import { GymManager } from '@/components/gym-manager';
import { DialogProvider } from '@/components/ui/dialog-provider';

beforeEach(async () => {
  await db.gyms.clear();
  await db.workoutSessions.clear();
});

it('crea un gimnasio y lo muestra en la lista', async () => {
  render(<DialogProvider><GymManager /></DialogProvider>);
  await userEvent.type(screen.getByPlaceholderText('Nombre del gimnasio'), "Gold's");
  await userEvent.click(screen.getByRole('button', { name: 'Añadir' }));
  await waitFor(() => expect(screen.getByText("Gold's")).toBeInTheDocument());
  expect(await db.gyms.count()).toBe(1);
});
