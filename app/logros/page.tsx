'use client';

import { useEffect, useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { useSetting } from '@/lib/use-setting';
import { getRachaSemanal, listPRs } from '@/lib/repositories/stats';
import { getAchievementMap } from '@/lib/repositories/achievements';
import { reconciliarLogros } from '@/lib/reconciliar-logros';
import { LogrosView } from '@/components/logros-view';

export default function LogrosPage() {
  const [objetivo] = useSetting<number>('objetivoSemanal', 3);
  const [ahora] = useState(() => Date.now());

  useEffect(() => {
    void reconciliarLogros(objetivo, ahora);
  }, [objetivo, ahora]);

  const racha = useLiveQuery(() => getRachaSemanal(objetivo, ahora), [objetivo, ahora]);
  const achievements = useLiveQuery(() => getAchievementMap(), []);
  const prs = useLiveQuery(() => listPRs(), []);

  return (
    <div className="space-y-6">
      <div>
        <p className="label-mono text-[11px] text-muted-foreground">Tu progreso</p>
        <h1 className="text-5xl">Logros</h1>
      </div>
      {racha === undefined || achievements === undefined || prs === undefined ? (
        <p className="text-muted-foreground">Cargando…</p>
      ) : (
        <LogrosView racha={racha} achievements={achievements} prs={prs} />
      )}
    </div>
  );
}
