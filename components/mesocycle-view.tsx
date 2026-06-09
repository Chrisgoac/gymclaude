'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import type { Mesocycle, Routine } from '@/lib/db/types';
import { semanaActual, deleteMesocycle } from '@/lib/repositories/mesocycles';
import { startSession } from '@/lib/repositories/workouts';
import { Button } from '@/components/ui/button';

export function MesocycleView({
  meso,
  routines,
  ahora: ahoraProp,
}: {
  meso: Mesocycle;
  routines: Routine[];
  ahora?: number;
}) {
  const router = useRouter();
  const [ahora] = useState<number>(() => ahoraProp ?? Date.now());
  const semana = semanaActual(meso, ahora);
  const dias = [...routines].sort((a, b) => a.orden - b.orden);

  async function empezar(routineId: string) {
    const s = await startSession({ routineId });
    router.push(`/entrenar/${s.id}`);
  }

  async function borrar() {
    await deleteMesocycle(meso.id);
    router.push('/rutinas');
  }

  return (
    <div className="space-y-4">
      <div className="brutal-box space-y-1 p-3">
        <h1 className="text-2xl font-bold">{meso.nombre}</h1>
        <p className="label-mono text-[10px] text-muted-foreground">
          {meso.objetivo} · {meso.semanas} semanas · {meso.diasPorSemana} días/semana
        </p>
        <p className="label-mono text-[10px] text-primary">Semana actual: {semana}/{meso.semanas}</p>
        {meso.notas && <p className="text-sm text-muted-foreground">{meso.notas}</p>}
      </div>

      <section className="brutal-box space-y-1 p-3">
        <h2 className="label-mono text-[10px] text-muted-foreground">Progresión</h2>
        <ul className="space-y-0.5">
          {meso.progresion.map((s) => (
            <li
              key={s.semana}
              className={`px-2 py-1 text-sm ${s.semana === semana ? 'border-2 border-foreground bg-primary/15 font-bold' : ''}`}
            >
              Sem {s.semana}{s.descarga ? ' (descarga)' : ''}: {s.ajuste}
            </li>
          ))}
        </ul>
      </section>

      <section className="space-y-2">
        <h2 className="label-mono text-[10px] text-muted-foreground">Días</h2>
        <ul className="brutal-box divide-y-2 divide-foreground">
          {dias.map((r) => (
            <li key={r.id} className="flex items-center justify-between gap-2 px-3 py-2.5">
              <span className="font-semibold">{r.nombre}</span>
              <Button size="sm" onClick={() => void empezar(r.id)}>
                Empezar
              </Button>
            </li>
          ))}
        </ul>
      </section>

      <button
        type="button"
        className="label-mono text-[11px] text-muted-foreground underline hover:text-destructive"
        onClick={() => void borrar()}
      >
        Borrar mesociclo
      </button>
    </div>
  );
}
