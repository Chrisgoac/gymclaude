import { db } from '@/lib/db/database';

export async function getSyncValue(key: string): Promise<number> {
  const row = await db.syncState.get(key);
  return row?.value ?? 0;
}

export async function setSyncValue(key: string, value: number): Promise<void> {
  await db.syncState.put({ key, value });
}

export const PUSH_WATERMARK = 'pushWatermark';
export const PULL_CURSOR = 'pullCursor';
