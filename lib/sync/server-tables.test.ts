// @vitest-environment node
import { describe, it, expect } from 'vitest';
import { withoutServerMeta } from './server-tables';

describe('withoutServerMeta', () => {
  it('drops server-only sync metadata and keeps record fields', () => {
    const rec = {
      id: 'e1', nombre: 'Press', updatedAt: 1, deletedAt: null,
      serverUpdatedAt: 2, serverVersion: 7, lastDeviceId: 'd', lastMutationId: 'm',
    };
    expect(withoutServerMeta(rec)).toEqual({ id: 'e1', nombre: 'Press', updatedAt: 1, deletedAt: null });
  });
});
