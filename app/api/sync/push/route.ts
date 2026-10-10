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
  const now = Date.now();
  // Un id de mutación por request: identifica en lastMutationId todo lo escrito por este push.
  const mutationId = crypto.randomUUID();

  await getPoolDb().transaction(async (tx) => {
    await lockUserSync(tx, userId);
    await applyLegacyPush(tx, userId, changes, { now, mutationId });
  });
  return NextResponse.json({ ok: true });
}
