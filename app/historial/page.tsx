'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { getCurrentStreakDays } from '@/lib/repositories/stats';
import { SessionSummaryList } from '@/components/session-summary-list';

export default function HistorialPage() {
  const racha = useLiveQuery(() => getCurrentStreakDays(), []);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Historial</h1>
        {racha !== undefined && racha > 0 && (
          <span className="text-sm font-medium text-primary">🔥 {racha} día{racha === 1 ? '' : 's'}</span>
        )}
      </div>
      <SessionSummaryList />
    </div>
  );
}
