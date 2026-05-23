import type { SyncMeta } from '@/lib/db/types';

export interface TableChanges {
  table: string;
  records: SyncMeta[];
}

export interface SyncTransport {
  push(changes: TableChanges[]): Promise<void>;
  pull(sinceCursor: number): Promise<{ changes: TableChanges[]; cursor: number }>;
}
