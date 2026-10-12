import { auth } from '@clerk/nextjs/server';
import { assertAccountActive } from '@/lib/account/access';
import { authRequired } from './errors';

export interface ApiPrincipal {
  userId: string;
}

type AuthSource = () => Promise<{
  userId?: string | null;
  factorVerificationAge?: [number, number] | null;
}>;

// Seam para tests: permite sustituir Clerk. `null` restaura el real.
let override: AuthSource | null = null;
export function setAuthSourceForTests(source: AuthSource | null): void {
  override = source;
}

export interface RequirePrincipalOptions {
  /**
   * Por defecto una cuenta con borrado pedido recibe 403 ACCOUNT_DELETING. Solo DELETE
   * /api/v1/account lo desactiva, para poder reanudar su propio borrado.
   */
  allowDeletingAccount?: boolean;
}

// Los clientes móviles envían `Authorization: Bearer <Clerk session token>`; proxy.ts
// (clerkMiddleware) lo verifica y auth() lo expone en el route handler.
export async function requirePrincipal(options: RequirePrincipalOptions = {}): Promise<ApiPrincipal> {
  let session: Awaited<ReturnType<AuthSource>>;
  try {
    session = await (override ?? auth)();
  } catch {
    throw authRequired();
  }
  const userId = session.userId;
  if (!userId) throw authRequired();
  if (!options.allowDeletingAccount) await assertAccountActive(userId);
  return { userId };
}

/**
 * Minutos desde la última verificación de primer y segundo factor de la sesión (claim `fva` del
 * session token de Clerk; -1 = nunca), o `null` si el token no lo trae. Solo lo usa el borrado de
 * cuenta para exigir autenticación reciente. Llamar después de requirePrincipal.
 */
export async function readFactorVerificationAge(): Promise<[number, number] | null> {
  try {
    return (await (override ?? auth)()).factorVerificationAge ?? null;
  } catch {
    return null;
  }
}
