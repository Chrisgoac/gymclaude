import { describe, it, expect, beforeEach, vi } from 'vitest';
import { db } from '@/lib/db/database';
import { createRoutine } from '@/lib/repositories/routines';
import { runSync } from '@/lib/sync/sync';
import { getSyncValue, PUSH_WATERMARK, PULL_CURSOR } from '@/lib/sync/state';
import type { SyncTransport, TableChanges } from '@/lib/sync/types';
import type { SyncMeta } from '@/lib/db/types';

beforeEach(async () => {
  await Promise.all([db.routines.clear(), db.syncState.clear()]);
});

it('envía los cambios locales sucios y avanza la marca de push', async () => {
  await createRoutine({ nombre: 'Local' });
  let enviado: TableChanges[] = [];
  const transport: SyncTransport = {
    push: vi.fn(async (c) => { enviado = c; }),
    pull: vi.fn(async () => ({ changes: [], cursor: 0 })),
  };
  await runSync(transport);
  expect(transport.push).toHaveBeenCalledTimes(1);
  expect(enviado.find((c) => c.table === 'routines')?.records).toHaveLength(1);
  expect(await getSyncValue(PUSH_WATERMARK)).toBeGreaterThan(0);
});

it('aplica los cambios recibidos del pull y guarda el cursor', async () => {
  const transport: SyncTransport = {
    push: vi.fn(async () => {}),
    pull: vi.fn(async () => ({
      changes: [{ table: 'routines', records: [
        { id: 'remote1', userId: null, nombre: 'Remota', archivada: false, updatedAt: 500, deletedAt: null } as unknown as SyncMeta,
      ] }],
      cursor: 999,
    })),
  };
  await runSync(transport);
  expect(await db.routines.get('remote1')).toBeDefined();
  expect(await getSyncValue(PULL_CURSOR)).toBe(999);
});

it('no llama a push si no hay cambios sucios', async () => {
  const start = Date.now();
  await db.syncState.put({ key: PUSH_WATERMARK, value: start });
  const transport: SyncTransport = {
    push: vi.fn(async () => {}),
    pull: vi.fn(async () => ({ changes: [], cursor: 0 })),
  };
  await runSync(transport);
  expect(transport.push).not.toHaveBeenCalled();
});
