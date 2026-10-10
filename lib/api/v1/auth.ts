import { auth } from '@clerk/nextjs/server';
import { authRequired } from './errors';

export interface ApiPrincipal {
  userId: string;
}

type AuthSource = () => Promise<{ userId?: string | null }>;

// Seam para tests: permite sustituir Clerk. `null` restaura el real.
let override: AuthSource | null = null;
export function setAuthSourceForTests(source: AuthSource | null): void {
  override = source;
}

// Los clientes móviles envían `Authorization: Bearer <Clerk session token>`; proxy.ts
// (clerkMiddleware) lo verifica y auth() lo expone en el route handler.
export async function requirePrincipal(): Promise<ApiPrincipal> {
  let userId: string | null | undefined;
  try {
    userId = (await (override ?? auth)()).userId;
  } catch {
    throw authRequired();
  }
  if (!userId) throw authRequired();
  return { userId };
}
