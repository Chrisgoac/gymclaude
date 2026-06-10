'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { useSetting } from '@/lib/use-setting';
import { getRachaSemanal } from '@/lib/repositories/stats';

export function StreakMini() {
  const [objetivo] = useSetting<number>('objetivoSemanal', 3);
  const [ahora] = useState(() => Date.now());
  const racha = useLiveQuery(() => getRachaSemanal(objetivo, ahora), [objetivo, ahora]);

  return (
    <Link
      href="/logros"
      aria-label="Ver racha y logros"
      className="brutal-box flex items-center justify-between gap-3 px-3 py-2.5 transition-transform active:translate-x-[2px] active:translate-y-[2px] active:shadow-[2px_2px_0_0_var(--color-foreground)]"
    >
      <span className="flex items-center gap-2">
        <span aria-hidden="true">🔥</span>
        <span className="font-semibold">Racha: {racha?.actual ?? 0} semanas</span>
      </span>
      <span className="label-mono text-[10px] text-muted-foreground">logros →</span>
    </Link>
  );
}
