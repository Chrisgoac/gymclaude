import { and, asc, gt, eq } from 'drizzle-orm';
import { syncChanges } from '@/db/schema';
import type { ApiPrincipal } from '@/lib/api/v1/auth';
import { SYNC_TABLE_NAMES, type PullResponse, type SyncTableName } from '@/lib/api/v1/contracts/sync';
import type { DbTransaction } from './push';
import { decodeCursor, encodeCursor } from './cursor';
import { SYNC_TABLE_ADAPTERS } from './tables';

const isSyncTable = (name: string): name is SyncTableName => (SYNC_TABLE_NAMES as readonly string[]).includes(name);

/**
 * Pull incremental por el diario global `sync_changes` del usuario.
 *
 * - Un registro puede tener varias filas de diario; solo la que coincide con su `serverVersion`
 *   actual (la última) emite cambio. Las anteriores se saltan pero avanzan el cursor.
 * - El cursor es la última `sequence` examinada, aunque la página salga con menos cambios que `limit`.
 * - Los tombstones (deletedAt) se emiten como cualquier registro.
 * - El orden de commit entre transacciones concurrentes lo serializa la ruta con un advisory lock
 *   por usuario; aquí no se gestiona.
 */
export async function pullChanges(
  tx: DbTransaction,
  principal: ApiPrincipal,
  cursor: string | null,
  limit: number,
): Promise<PullResponse> {
  const after = decodeCursor(cursor);
  const journal = await tx
    .select()
    .from(syncChanges)
    .where(and(eq(syncChanges.userId, principal.userId), gt(syncChanges.sequence, after)))
    .orderBy(asc(syncChanges.sequence))
    .limit(limit + 1);

  const hasMore = journal.length > limit;
  const page = hasMore ? journal.slice(0, limit) : journal;
  if (page.length === 0) return { changes: [], cursor: cursor ?? encodeCursor(BigInt(0)), hasMore: false };

  const idsByTable = new Map<SyncTableName, Set<string>>();
  for (const j of page) {
    if (!isSyncTable(j.tableName)) continue;
    const ids = idsByTable.get(j.tableName) ?? new Set<string>();
    ids.add(j.recordId);
    idsByTable.set(j.tableName, ids);
  }

  const rowsByKey = new Map<string, Record<string, unknown>>();
  for (const [table, ids] of idsByTable) {
    const adapter = SYNC_TABLE_ADAPTERS[table];
    for (const row of await adapter.fetchOwned(tx, principal.userId, [...ids])) {
      rowsByKey.set(`${table}\0${row.id as string}`, row);
    }
  }

  const changes: PullResponse['changes'] = [];
  for (const j of page) {
    if (!isSyncTable(j.tableName)) continue;
    const row = rowsByKey.get(`${j.tableName}\0${j.recordId}`);
    if (!row || row.serverVersion !== j.sequence) continue; // entrada antigua: la última la cubre
    // toColumns es el mapeo explícito de campos de dominio: userId y metadatos de servidor no salen.
    const record = SYNC_TABLE_ADAPTERS[j.tableName].toColumns(row as never);
    changes.push({ table: j.tableName, record, serverVersion: j.sequence.toString() } as PullResponse['changes'][number]);
  }

  return { changes, cursor: encodeCursor(page[page.length - 1].sequence), hasMore };
}
