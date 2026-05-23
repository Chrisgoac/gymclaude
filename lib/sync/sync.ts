import type { SyncTransport } from './types';
import { collectDirty } from './collect';
import { applyIncoming } from './apply';
import { getSyncValue, setSyncValue, PUSH_WATERMARK, PULL_CURSOR } from './state';

export async function runSync(transport: SyncTransport): Promise<void> {
  const startTime = Date.now();
  const prevWatermark = await getSyncValue(PUSH_WATERMARK);
  const dirty = await collectDirty(prevWatermark);
  if (dirty.length > 0) {
    await transport.push(dirty);
  }
  await setSyncValue(PUSH_WATERMARK, startTime);

  const cursor = await getSyncValue(PULL_CURSOR);
  const { changes, cursor: newCursor } = await transport.pull(cursor);
  if (changes.length > 0) {
    await applyIncoming(changes);
  }
  await setSyncValue(PULL_CURSOR, newCursor);
}
