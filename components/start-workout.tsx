'use client';

import { useRouter } from 'next/navigation';
import { ArrowRight } from 'lucide-react';
import { useLiveQuery } from 'dexie-react-hooks';
import { listRoutines, listDays } from '@/lib/repositories/routines';
import { startSession } from '@/lib/repositories/workouts';
import type { Routine } from '@/lib/db/types';
import { Button } from '@/components/ui/button';

export function StartWorkout() {
  const router = useRouter();
  const routines = useLiveQuery(() => listRoutines(), []);

  async function empezarLibre() {
    const s = await startSession({});
    router.push(`/entrenar/${s.id}`);
  }

  async function empezarDia(routineDayId: string) {
    const s = await startSession({ routineDayId });
    router.push(`/entrenar/${s.id}`);
  }

  return (
    <div className="space-y-7">
      <Button
        onClick={empezarLibre}
        size="lg"
        className="w-full font-[family-name:var(--font-display)] text-xl tracking-wide"
      >
        Empezar entreno libre
        <ArrowRight className="size-5" strokeWidth={3} />
      </Button>

      {(routines ?? []).length > 0 && (
        <section className="space-y-3">
          <h2 className="label-mono text-[11px] text-muted-foreground">Desde una rutina</h2>
          {(routines ?? []).map((r) => (
            <RoutineDaysToStart key={r.id} routine={r} onStart={empezarDia} />
          ))}
        </section>
      )}
    </div>
  );
}

function RoutineDaysToStart({ routine, onStart }: { routine: Routine; onStart: (dayId: string) => void }) {
  const dias = useLiveQuery(() => listDays(routine.id), [routine.id]);
  if ((dias ?? []).length === 0) return null;
  return (
    <div className="brutal-box">
      <p className="border-b-2 border-foreground bg-foreground px-3 py-2 font-[family-name:var(--font-display)] text-lg uppercase tracking-wide text-background">
        {routine.nombre}
      </p>
      <ul>
        {(dias ?? []).map((d) => (
          <li
            key={d.id}
            className="flex items-center justify-between gap-2 border-b-2 border-foreground px-3 py-2.5 last:border-b-0"
          >
            <span className="font-semibold">{d.nombre}</span>
            <button
              className="label-mono inline-flex items-center gap-1 border-2 border-foreground bg-primary px-2.5 py-1.5 text-[10px] text-primary-foreground transition-transform active:translate-x-[2px] active:translate-y-[2px]"
              onClick={() => onStart(d.id)}
            >
              Empezar <ArrowRight className="size-3" strokeWidth={3} />
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
