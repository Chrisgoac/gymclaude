'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { Sparkles } from 'lucide-react';
import { listMesocycles } from '@/lib/repositories/mesocycles';

export function ActiveMesocycleCard() {
  const mesos = useLiveQuery(() => listMesocycles(), []);
  const activo = mesos?.[0]; // listMesocycles ya ordena por fechaInicio desc
  if (!activo) return null;
  return (
    <Link
      href={`/mesociclo/${activo.id}`}
      className="brutal-box flex items-center justify-between gap-3 px-3 py-2.5 transition-transform active:translate-x-[2px] active:translate-y-[2px]"
    >
      <span className="flex items-center gap-2">
        <Sparkles className="size-4 text-primary" strokeWidth={2} aria-hidden="true" />
        <span className="font-semibold">{activo.nombre}</span>
      </span>
      <span className="label-mono text-[10px] text-muted-foreground">mesociclo →</span>
    </Link>
  );
}
