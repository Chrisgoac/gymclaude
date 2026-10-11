'use server';

import { auth, reverificationError } from '@clerk/nextjs/server';
import { deleteAccount, DELETION_CONFIRMATION, RECENT_AUTH_MAX_MINUTES } from '@/lib/account/deletion';
import { getAccountDeletionService } from '@/lib/account/runtime';
import { ApiError } from '@/lib/api/v1/errors';
import { enforceRateLimit } from '@/lib/api/v1/rate-limit';

export type DeleteAccountActionResult =
  | { status: 'deleted' }
  | { status: 'in_progress' }
  | { status: 'error'; code: 'AUTH_REQUIRED' | 'CONFIRMATION_REQUIRED' | 'UNAVAILABLE' | 'RATE_LIMITED' };

// Server Action de /delete-account: alcanzable por POST directo, así que comprueba sesión,
// confirmación y autenticación reciente por sí misma (igual que DELETE /api/v1/account, mismo caso de
// uso). Sin verificación reciente devuelve el aviso de reverificación de Clerk: `useReverification`
// en el formulario abre el modal de Clerk y repite la llamada.
export async function requestAccountDeletion(
  confirm: string,
): Promise<DeleteAccountActionResult | ReturnType<typeof reverificationError>> {
  const session = await auth();
  if (!session.userId) return { status: 'error', code: 'AUTH_REQUIRED' };
  if (confirm !== DELETION_CONFIRMATION) {
    return { status: 'error', code: 'CONFIRMATION_REQUIRED' };
  }
  try {
    const userId = session.userId;
    const result = await deleteAccount(getAccountDeletionService(), {
      userId,
      factorVerificationAge: session.factorVerificationAge,
      beforeWork: () => enforceRateLimit('account', userId),
    });
    return { status: result.status };
  } catch (error) {
    if (error instanceof ApiError && error.code === 'REAUTH_REQUIRED') {
      return reverificationError({ level: 'first_factor', afterMinutes: RECENT_AUTH_MAX_MINUTES });
    }
    if (error instanceof ApiError && error.code === 'RATE_LIMITED') {
      return { status: 'error', code: 'RATE_LIMITED' };
    }
    console.error('[delete-account] request failed', error instanceof ApiError ? error.code : 'unexpected');
    return { status: 'error', code: 'UNAVAILABLE' };
  }
}
