import { syncChanges } from '@/db/schema';
import type { PoolTx } from '@/db/pool';
import { SYNC_TABLE_NAMES, type SyncTableName } from '@/lib/api/v1/contracts/sync';
import { ApiError } from '@/lib/api/v1/errors';
import { enqueueReleasedMediaKey } from '@/lib/api/v1/media/deletions';
import { resolveServerWrite } from '@/lib/sync/server-merge';
import { withoutServerMeta } from '@/lib/sync/server-tables';
import type { TableChanges } from '@/lib/sync/types';
import { SYNC_TABLE_ADAPTERS, type RowWrite, type SyncTableAdapter } from './tables';

/** `lastDeviceId` de toda escritura que llega por el push legacy de la PWA. */
export const LEGACY_DEVICE_ID = 'legacy-pwa';

const isSyncTable = (name: string): name is SyncTableName => (SYNC_TABLE_NAMES as readonly string[]).includes(name);

/**
 * Aplica un push legacy (PWA) dentro de `tx`, que ya tiene el lock de sync del usuario. Conserva
 * el LWW legacy (`updatedAt` entrante >= guardado gana) pero cada escritura aceptada entra en el
 * diario `sync_changes` y fija `serverVersion`, para que la sync v1 la vea en su siguiente pull.
 * No hay fila en `sync_mutations`: el cliente legacy no manda mutationId que deduplicar.
 */
export async function applyLegacyPush(
  tx: PoolTx,
  userId: string,
  changes: TableChanges[],
  options: { now: number; mutationId: string },
): Promise<void> {
  const { now, mutationId } = options;
  for (const { table, records } of changes) {
    if (!isSyncTable(table)) continue; // tabla desconocida: se ignora, como antes
    const adapter = SYNC_TABLE_ADAPTERS[table] as SyncTableAdapter<SyncTableName>;
    for (const rec of records as unknown as Record<string, unknown>[]) {
      await applyLegacyRecord(tx, adapter, table, userId, rec, now, mutationId);
    }
  }
}

async function applyLegacyRecord(
  tx: PoolTx,
  adapter: SyncTableAdapter<SyncTableName>,
  table: SyncTableName,
  userId: string,
  rec: Record<string, unknown>,
  now: number,
  mutationId: string,
): Promise<void> {
  const id = rec.id as string;
  let existing;
  try {
    existing = await adapter.lockOwnedRow(tx, userId, id);
  } catch (e) {
    // Id de otro usuario: se descarta el registro (antes el upsert le robaba la fila).
    if (e instanceof ApiError && e.code === 'RECORD_ID_CONFLICT') return;
    throw e;
  }
  if (!resolveServerWrite(existing?.updatedAt, rec.updatedAt as number)) return;

  // El cliente no puede fijar metadatos de servidor (serverVersion, lastDeviceId…).
  const values = {
    ...withoutServerMeta(rec),
    userId,
    lastDeviceId: LEGACY_DEVICE_ID,
    lastMutationId: mutationId,
    serverUpdatedAt: now,
  } as RowWrite;
  // Sin fila previa: la inserción puede perder contra otro usuario que cree ese id a la vez.
  if (!existing && !(await adapter.insertIfAbsent(tx, values))) return;

  const [change] = await tx
    .insert(syncChanges)
    .values({ userId, tableName: table, recordId: id, changedAt: now })
    .returning({ sequence: syncChanges.sequence });
  await adapter.update(tx, userId, id, existing ? { ...values, serverVersion: change.sequence } : { serverVersion: change.sequence });
  // Una foto v1 tombstoneada desde la PWA libera su objeto privado: borrado diferido, misma tx.
  await enqueueReleasedMediaKey(tx, {
    table,
    userId,
    before: existing ? { key: existing.row.key, deletedAt: existing.deletedAt } : null,
    after: values,
    at: now,
  });
}
