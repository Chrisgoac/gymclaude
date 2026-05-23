'use client';

import { useRouter } from 'next/navigation';
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
    <div className="space-y-6">
      <Button onClick={empezarLibre} className="w-full">
        Empezar entreno libre
      </Button>

      {(routines ?? []).length > 0 && (
        <section className="space-y-3">
          <h2 className="text-sm font-semibold uppercase text-muted-foreground">Desde una rutina</h2>
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
    <div className="rounded-md border p-3">
      <p className="mb-2 font-medium">{routine.nombre}</p>
      <ul className="space-y-1">
        {(dias ?? []).map((d) => (
          <li key={d.id} className="flex items-center justify-between">
            <span className="text-sm">{d.nombre}</span>
            <button className="text-sm text-primary" onClick={() => onStart(d.id)}>
              Empezar
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
