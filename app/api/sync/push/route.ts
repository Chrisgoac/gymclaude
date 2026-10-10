import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { getPoolDb } from '@/db/pool';
import { applyLegacyPush } from '@/lib/api/v1/sync/legacy';
import { lockUserSync } from '@/lib/api/v1/sync/lock';
import type { TableChanges } from '@/lib/sync/types';

// Push legacy de la PWA: mismo request/response de siempre, pero escribe en el diario de la sync v1
// (serverVersion + sync_changes) dentro de una transacción con el lock de sync del usuario.
export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });

  const changes = (await req.json()) as TableChanges[];
  // Un id de mutación por request: identifica en lastMutationId todo lo escrito por este push.
  const mutationId = crypto.randomUUID();

  await getPoolDb().transaction(async (tx) => {
    await lockUserSync(tx, userId);
    // `now` se lee ya con el lock: si se tomara antes, una request que esperó el lock escribiría
    // un serverUpdatedAt anterior al de la que confirmó primero y el pull legacy podría saltarla.
    await applyLegacyPush(tx, userId, changes, { now: Date.now(), mutationId });
  });
  return NextResponse.json({ ok: true });
}
