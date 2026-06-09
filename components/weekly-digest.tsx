'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { getWeeklySummary, getPRsThisWeek } from '@/lib/repositories/stats';
import { useSetting } from '@/lib/use-setting';
import { StatCard } from '@/components/stat-card';

export function WeeklyDigest({ gymId }: { gymId?: string }) {
  const resumen = useLiveQuery(() => getWeeklySummary(gymId), [gymId]);
  const prs = useLiveQuery(() => getPRsThisWeek(gymId), [gymId]);
  const [objetivo] = useSetting<number>('objetivoSemanal', 3);
  if (!resumen) return null;
  const delta = resumen.deltaPct;
  return (
    <div className="space-y-2">
      <div className="grid grid-cols-3 gap-2">
        <StatCard valor={`${resumen.sesiones}/${objetivo}`} unidad="sesiones" destacado />
        <StatCard valor={`${prs?.length ?? 0}`} unidad="PR semana" />
        <StatCard valor={delta == null ? '—' : `${delta >= 0 ? '▲' : '▼'}${Math.abs(delta)}%`} unidad="vol vs previa" />
      </div>
      {prs && prs.length > 0 && (
        <ul className="brutal-box divide-y-2 divide-foreground" aria-label="PRs esta semana">
          {prs.map((p) => (
            <li key={p.exerciseId} className="flex items-center justify-between gap-2 px-3 py-2">
              <span className="font-medium">{p.nombre}</span>
              <span className="label-mono text-[10px] text-primary">PR {p.tipo === 'peso' ? 'peso' : '1RM'}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
