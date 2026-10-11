import { clerkClient } from '@clerk/nextjs/server';
import { isClerkAPIResponseError } from '@clerk/nextjs/errors';
import { getPoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import { r2AccountStorage, r2PrivateConfigured } from '@/lib/r2/client';
import { createDbEntitlementStore } from '@/lib/billing/entitlements';
import { isAccountDeleting } from './access';
import {
  activeSubscriptionFor,
  createAccountDeletionService,
  type AccountDeletionService,
  type ActiveSubscription,
  type IdentityProvider,
} from './deletion';

/** Clerk Backend API (CLERK_SECRET_KEY). Un 404 significa que ya no existe: éxito idempotente. */
export const clerkIdentityProvider: IdentityProvider = {
  async deleteUser(userId) {
    try {
      await (await clerkClient()).users.deleteUser(userId);
    } catch (error) {
      if (isClerkAPIResponseError(error) && error.status === 404) return;
      throw error;
    }
  },
};

/**
 * Ambos buckets R2 tienen que estar configurados para garantizar el borrado de los objetos (el
 * privado v1 y el público legacy de la PWA). Sin ellos se responde 503 ANTES de bloquear la cuenta.
 */
export function accountDeletionConfigured(): boolean {
  return r2PrivateConfigured() && Boolean(process.env.R2_BUCKET);
}

export const deletionUnavailable = () =>
  new ApiError(503, 'DELETION_UNAVAILABLE', 'Account deletion is temporarily unavailable', true);

/** Servicio de producción (Postgres + R2 + Clerk). Los tests de ruta mockean este módulo. */
export function getAccountDeletionService(): AccountDeletionService {
  if (!accountDeletionConfigured()) throw deletionUnavailable();
  return createAccountDeletionService({ db: getPoolDb(), storage: r2AccountStorage, identity: clerkIdentityProvider });
}

export interface DeletionPageState {
  /** Ya hay un borrado pedido (la cuenta está bloqueada). */
  deleting: boolean;
  activeSubscription: ActiveSubscription | null;
}

/** Lo que la página /delete-account necesita mostrar antes de confirmar. */
export async function loadDeletionPageState(userId: string): Promise<DeletionPageState> {
  const db = getPoolDb();
  const [deleting, activeSubscription] = await Promise.all([
    isAccountDeleting(db, userId),
    activeSubscriptionFor(createDbEntitlementStore(() => db), userId, Date.now()),
  ]);
  return { deleting, activeSubscription };
}
