import { beforeEach, describe, expect, it, vi } from 'vitest';
// The account-deletion access lock has its own tests (app/api/v1/account, lib/account).
vi.mock('@/lib/account/access', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/account/access')>()),
  legacyDeletingResponse: async () => null,
}));

const auth = vi.fn();
vi.mock('@clerk/nextjs/server', () => ({ auth: () => auth() }));
// Doble de la consulta neon-http: select().from(table).where(...) → filas de esa tabla.
const rowsByTable = new Map<unknown, Record<string, unknown>[]>();
vi.mock('@/db/client', () => ({
  db: {
    select: () => ({ from: (table: unknown) => ({ where: async () => rowsByTable.get(table) ?? [] }) }),
  },
}));

import { GET } from './route';
import { SERVER_TABLES } from '@/lib/sync/server-tables';

beforeEach(() => {
  auth.mockReset();
  rowsByTable.clear();
});

describe('GET /api/sync/pull (legacy PWA)', () => {
  it('keeps the plain-text 401 without a session', async () => {
    auth.mockResolvedValue({ userId: null });
    const res = await GET(new Request('http://x/api/sync/pull'));
    expect(res.status).toBe(401);
    expect(await res.text()).toBe('Unauthorized');
  });

  it('returns { changes, cursor } with server metadata stripped and the max serverUpdatedAt as cursor', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    rowsByTable.set(SERVER_TABLES.gyms, [
      {
        id: 'g1', userId: 'u1', updatedAt: 5, deletedAt: null, nombre: 'G', orden: 0, archivada: false,
        serverUpdatedAt: 700, serverVersion: BigInt(12), lastDeviceId: 'legacy-pwa', lastMutationId: 'm',
      },
    ]);
    rowsByTable.set(SERVER_TABLES.userSettings, [
      { id: 'units', userId: 'u1', updatedAt: 6, deletedAt: null, valor: '"kg"', serverUpdatedAt: 900, serverVersion: BigInt(13) },
    ]);
    const res = await GET(new Request('http://x/api/sync/pull?cursor=100'));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({
      changes: [
        { table: 'gyms', records: [{ id: 'g1', userId: 'u1', updatedAt: 5, deletedAt: null, nombre: 'G', orden: 0, archivada: false }] },
        { table: 'userSettings', records: [{ id: 'units', userId: 'u1', updatedAt: 6, deletedAt: null, valor: '"kg"' }] },
      ],
      cursor: 900,
    });
  });

  it('echoes the incoming cursor when nothing changed', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await GET(new Request('http://x/api/sync/pull?cursor=42'));
    expect(await res.json()).toEqual({ changes: [], cursor: 42 });
  });
});
