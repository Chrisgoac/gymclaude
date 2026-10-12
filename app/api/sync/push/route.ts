import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { legacyDeletingResponse } from '@/lib/account/access';
import { getPoolDb } from '@/db/pool';
import { ApiError } from '@/lib/api/v1/errors';
import { applyLegacyPush } from '@/lib/api/v1/sync/legacy';
import { lockUserSync } from '@/lib/api/v1/sync/lock';
import type { TableChanges } from '@/lib/sync/types';

// Push legacy de la PWA: mismo request/response de siempre, pero escribe en el diario de la sync v1
// (serverVersion + sync_changes) dentro de una transacción con el lock de sync del usuario.
export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  const deleting = await legacyDeletingResponse(userId);
  if (deleting) return deleting;

  const changes = (await req.json()) as TableChanges[];
  // Un id de mutación por request: identifica en lastMutationId todo lo escrito por este push.
  const mutationId = crypto.randomUUID();

  try {
    await getPoolDb().transaction(async (tx) => {
      // También niega (ACCOUNT_DELETING) un push que esperaba el lock mientras se pedía el borrado.
      await lockUserSync(tx, userId);
      // `now` se lee ya con el lock: si se tomara antes, una request que esperó el lock escribiría
      // un serverUpdatedAt anterior al de la que confirmó primero y el pull legacy podría saltarla.
      await applyLegacyPush(tx, userId, changes, { now: Date.now(), mutationId });
    });
  } catch (error) {
    if (error instanceof ApiError && error.code === 'ACCOUNT_DELETING') {
      return new NextResponse('ACCOUNT_DELETING', { status: 403 });
    }
    throw error;
  }
  return NextResponse.json({ ok: true });
}
