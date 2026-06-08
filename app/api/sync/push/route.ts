/* eslint-disable @typescript-eslint/no-explicit-any */
import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { and, eq } from 'drizzle-orm';
import { db } from '@/db/client';
import { SERVER_TABLES } from '@/lib/sync/server-tables';
import { resolveServerWrite } from '@/lib/sync/server-merge';
import type { TableChanges } from '@/lib/sync/types';

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });

  const changes = (await req.json()) as TableChanges[];
  const serverUpdatedAt = Date.now();

  for (const { table: name, records } of changes) {
    const table = SERVER_TABLES[name] as any;
    if (!table) continue;
    for (const rec of records as any[]) {
      // El filtro por (id, userId) localiza la fila correcta también para user_settings (PK compuesta).
      const existing = await db
        .select({ updatedAt: table.updatedAt })
        .from(table)
        .where(and(eq(table.id, rec.id), eq(table.userId, userId)))
        .limit(1);
      if (!resolveServerWrite(existing[0]?.updatedAt, rec.updatedAt)) continue;
      const values = { ...rec, userId, serverUpdatedAt };
      // user_settings tiene PK compuesta (user_id, id); las demás tablas, id simple.
      const conflictTarget = name === 'userSettings' ? [table.userId, table.id] : table.id;
      await db.insert(table).values(values).onConflictDoUpdate({ target: conflictTarget, set: values });
    }
  }
  return NextResponse.json({ ok: true });
}
