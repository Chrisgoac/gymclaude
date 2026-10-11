'use client';

import { useState } from 'react';
import { useClerk, useReverification } from '@clerk/nextjs';
import { Button } from '@/components/ui/button';
import { requestAccountDeletion, type DeleteAccountActionResult } from './actions';

const CONFIRMATION = 'ELIMINAR';

type FormState =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'deleted' }
  | { kind: 'in_progress' }
  | { kind: 'error'; message: string };

const ERROR_MESSAGES: Record<Extract<DeleteAccountActionResult, { status: 'error' }>['code'], string> = {
  AUTH_REQUIRED: 'Tu sesión ha caducado. Vuelve a iniciar sesión e inténtalo de nuevo.',
  CONFIRMATION_REQUIRED: `Escribe ${CONFIRMATION} para confirmar.`,
  UNAVAILABLE: 'No se ha podido borrar la cuenta ahora mismo. Inténtalo de nuevo en unos minutos.',
  RATE_LIMITED: 'Demasiados intentos seguidos. Espera un minuto e inténtalo de nuevo.',
};

export function DeleteAccountForm() {
  const [confirm, setConfirm] = useState('');
  const [state, setState] = useState<FormState>({ kind: 'idle' });
  const { signOut } = useClerk();
  // Si Clerk pide reverificación (sesión sin verificación reciente), abre su modal y repite.
  const request = useReverification(requestAccountDeletion);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (confirm !== CONFIRMATION) return;
    setState({ kind: 'submitting' });
    try {
      const result = await request(confirm);
      if (result.status === 'deleted') {
        setState({ kind: 'deleted' });
        await signOut({ redirectUrl: '/delete-account?deleted=1' }).catch(() => {});
      } else if (result.status === 'in_progress') {
        setState({ kind: 'in_progress' });
      } else {
        setState({ kind: 'error', message: ERROR_MESSAGES[result.code] });
      }
    } catch {
      // Incluye cancelar el modal de reverificación de Clerk.
      setState({ kind: 'error', message: 'No se ha confirmado tu identidad. No se ha borrado nada.' });
    }
  }

  if (state.kind === 'deleted') {
    return (
      <p role="status" className="border-2 border-foreground bg-card p-4">
        Tu cuenta y todos tus datos se han borrado. Cerrando sesión…
      </p>
    );
  }
  if (state.kind === 'in_progress') {
    return (
      <p role="status" className="border-2 border-foreground bg-card p-4">
        Tu cuenta ya está bloqueada y el borrado está en curso. Terminará automáticamente en las próximas
        horas; no necesitas hacer nada más.
      </p>
    );
  }

  const ready = confirm === CONFIRMATION;
  return (
    <form onSubmit={submit} className="space-y-3" aria-label="Confirmar borrado de la cuenta">
      <label htmlFor="delete-confirm" className="block text-sm font-medium">
        Escribe <strong>{CONFIRMATION}</strong> para confirmar
      </label>
      <input
        id="delete-confirm"
        name="confirm"
        autoComplete="off"
        autoCapitalize="characters"
        spellCheck={false}
        value={confirm}
        onChange={(e) => setConfirm(e.target.value.trim())}
        className="w-full border-2 border-foreground bg-background px-3 py-2 font-mono uppercase"
      />
      {state.kind === 'error' && (
        <p role="alert" className="text-sm text-destructive">
          {state.message}
        </p>
      )}
      <Button type="submit" variant="destructive" className="w-full" disabled={!ready || state.kind === 'submitting'}>
        {state.kind === 'submitting' ? 'Borrando…' : 'Borrar mi cuenta definitivamente'}
      </Button>
    </form>
  );
}
