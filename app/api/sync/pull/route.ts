/* eslint-disable @typescript-eslint/no-explicit-any */
import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { and, eq, gte } from 'drizzle-orm';
import { db } from '@/db/client';
import { SERVER_TABLES } from '@/lib/sync/server-tables';
import type { TableChanges } from '@/lib/sync/types';

export async function GET(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });

  const cursor = Number(new URL(req.url).searchParams.get('cursor') ?? '0');
  const changes: TableChanges[] = [];
  let maxSeen = cursor;

  for (const [name, t] of Object.entries(SERVER_TABLES)) {
    const table = t as any;
    const rows = (await db
      .select()
      .from(table)
      .where(and(eq(table.userId, userId), gte(table.serverUpdatedAt, cursor)))) as any[];
    if (rows.length === 0) continue;
    for (const r of rows) if (r.serverUpdatedAt > maxSeen) maxSeen = r.serverUpdatedAt;
    const records = rows.map(({ serverUpdatedAt: _s, ...rest }) => rest);
    changes.push({ table: name, records });
  }
  return NextResponse.json({ changes, cursor: maxSeen });
}
