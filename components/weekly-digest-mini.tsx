'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { getWeeklySummary, getPRsThisWeek } from '@/lib/repositories/stats';
import { useSetting } from '@/lib/use-setting';

export function WeeklyDigestMini() {
  const resumen = useLiveQuery(() => getWeeklySummary(), []);
  const prs = useLiveQuery(() => getPRsThisWeek(), []);
  const [objetivo] = useSetting<number>('objetivoSemanal', 3);
  // useLiveQuery devuelve undefined mientras carga; getWeeklySummary siempre devuelve objeto (sesiones puede ser 0).
  if (!resumen) return null;
  const nPRs = prs?.length ?? 0;
  const delta = resumen.deltaPct;
  return (
    <Link href="/progreso" className="brutal-box block px-3 py-2.5 transition-transform active:translate-x-[2px] active:translate-y-[2px]" aria-label="Resumen semanal — ir a Progreso">
      <p className="label-mono text-[10px] text-muted-foreground">Esta semana</p>
      <p className="font-medium">
        {resumen.sesiones}/{objetivo} sesiones · {nPRs} {nPRs === 1 ? 'PR' : 'PRs'}
        {delta != null && ` · vol ${delta >= 0 ? '▲' : '▼'}${Math.abs(delta)}%`}
      </p>
    </Link>
  );
}
