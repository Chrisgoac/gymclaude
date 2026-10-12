import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { DeletionPageState } from '@/lib/account/runtime';

const h = vi.hoisted(() => ({
  userId: null as string | null,
  state: { deleting: false, activeSubscription: null } as DeletionPageState,
  action: vi.fn<(confirm: string) => Promise<unknown>>(),
  signOut: vi.fn(async () => {}),
}));
vi.mock('@clerk/nextjs/server', () => ({ auth: async () => ({ userId: h.userId }) }));
vi.mock('@clerk/nextjs', () => ({
  SignInButton: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useClerk: () => ({ signOut: h.signOut }),
  // The real hook opens Clerk's reverification modal; here it just forwards the call.
  useReverification: (fetcher: (...a: unknown[]) => Promise<unknown>) => fetcher,
}));
vi.mock('@/lib/account/runtime', () => ({ loadDeletionPageState: async () => h.state }));
vi.mock('./actions', () => ({ requestAccountDeletion: (confirm: string) => h.action(confirm) }));

import DeleteAccountPage from './page';

async function renderPage(search: Record<string, string> = {}) {
  render(await DeleteAccountPage({ searchParams: Promise.resolve(search) }));
}

beforeEach(() => {
  h.userId = null;
  h.state = { deleting: false, activeSubscription: null };
  h.action.mockReset();
  h.signOut.mockClear();
});
afterEach(cleanup);

describe('/delete-account (public page)', () => {
  it('explains what is deleted and that the Play subscription is cancelled separately, to anyone', async () => {
    await renderPage();
    expect(screen.getByRole('heading', { level: 1, name: /borrar tu cuenta/i })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /qué se borra/i })).toBeInTheDocument();
    expect(screen.getByText(/entrenos, rutinas/i)).toBeInTheDocument();
    expect(screen.getByText(/no cancela/i)).toBeInTheDocument();
    expect(screen.getByText(/no se puede pasar a una cuenta nueva/i)).toBeInTheDocument();
    const play = screen.getByRole('link', { name: /suscripciones en google play/i });
    expect(play).toHaveAttribute('href', expect.stringMatching(/^https:\/\/play\.google\.com\/store\/account\/subscriptions\?package=com\.chrisgoac\.gymlog/));
  });

  it('signed out: shows a sign-in button and no deletion form', async () => {
    await renderPage();
    expect(screen.getByRole('button', { name: /iniciar sesión/i })).toBeInTheDocument();
    expect(screen.queryByRole('form', { name: /confirmar borrado/i })).not.toBeInTheDocument();
  });

  it('signed out after deleting: confirms the deletion', async () => {
    await renderPage({ deleted: '1' });
    expect(screen.getByRole('status')).toHaveTextContent(/se ha borrado/i);
  });

  it('signed in with a renewing subscription: warns before the confirmation form', async () => {
    h.userId = 'user_1';
    h.state = {
      deleting: false,
      activeSubscription: {
        productId: 'gymlog_pro_annual', state: 'ACTIVE', validUntil: Date.UTC(2027, 0, 1), willRenew: true,
        manageUrl: 'https://play.google.com/store/account/subscriptions?package=com.chrisgoac.gymlog&sku=gymlog_pro_annual',
      },
    };
    await renderPage();
    const note = screen.getByRole('note');
    expect(note).toHaveTextContent(/seguirá cobrándote/i);
    expect(within(note).getByRole('link', { name: /gestionar la suscripción/i })).toHaveAttribute('href', expect.stringContaining('sku=gymlog_pro_annual'));
    expect(screen.getByRole('form', { name: /confirmar borrado/i })).toBeInTheDocument();
  });

  it('signed in while a deletion is already in progress: no form', async () => {
    h.userId = 'user_1';
    h.state = { deleting: true, activeSubscription: null };
    await renderPage();
    expect(screen.getByRole('status')).toHaveTextContent(/en curso/i);
    expect(screen.queryByRole('form', { name: /confirmar borrado/i })).not.toBeInTheDocument();
  });
});

describe('deletion form', () => {
  beforeEach(() => {
    h.userId = 'user_1';
  });

  it('stays disabled until ELIMINAR is typed', async () => {
    await renderPage();
    const button = screen.getByRole('button', { name: /borrar mi cuenta/i });
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/escribe/i), 'ELIMINA');
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/escribe/i), 'R');
    expect(button).toBeEnabled();
  });

  it('on success reports the deletion and signs out', async () => {
    h.action.mockResolvedValue({ status: 'deleted' });
    await renderPage();
    await userEvent.type(screen.getByLabelText(/escribe/i), 'ELIMINAR');
    await userEvent.click(screen.getByRole('button', { name: /borrar mi cuenta/i }));
    expect(h.action).toHaveBeenCalledWith('ELIMINAR');
    expect(await screen.findByRole('status')).toHaveTextContent(/se han borrado/i);
    await waitFor(() => expect(h.signOut).toHaveBeenCalledWith({ redirectUrl: '/delete-account?deleted=1' }));
  });

  it('reports a locked, still running deletion', async () => {
    h.action.mockResolvedValue({ status: 'in_progress' });
    await renderPage();
    await userEvent.type(screen.getByLabelText(/escribe/i), 'ELIMINAR');
    await userEvent.click(screen.getByRole('button', { name: /borrar mi cuenta/i }));
    expect(await screen.findByRole('status')).toHaveTextContent(/bloqueada/i);
    expect(h.signOut).not.toHaveBeenCalled();
  });

  it('shows an error and keeps the form when the server cannot delete now', async () => {
    h.action.mockResolvedValue({ status: 'error', code: 'UNAVAILABLE' });
    await renderPage();
    await userEvent.type(screen.getByLabelText(/escribe/i), 'ELIMINAR');
    await userEvent.click(screen.getByRole('button', { name: /borrar mi cuenta/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/no se ha podido borrar/i);
    expect(screen.getByRole('button', { name: /borrar mi cuenta/i })).toBeEnabled();
  });

  it('treats a cancelled reverification as "nothing deleted"', async () => {
    h.action.mockRejectedValue(new Error('reverification cancelled'));
    await renderPage();
    await userEvent.type(screen.getByLabelText(/escribe/i), 'ELIMINAR');
    await userEvent.click(screen.getByRole('button', { name: /borrar mi cuenta/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/no se ha borrado nada/i);
  });
});
