import type { SyncTransport } from './types';

export const httpTransport: SyncTransport = {
  async push(changes) {
    const res = await fetch('/api/sync/push', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(changes),
    });
    if (!res.ok) throw new Error(`push falló: ${res.status}`);
  },
  async pull(cursor) {
    const res = await fetch(`/api/sync/pull?cursor=${cursor}`);
    if (!res.ok) throw new Error(`pull falló: ${res.status}`);
    return res.json();
  },
};
