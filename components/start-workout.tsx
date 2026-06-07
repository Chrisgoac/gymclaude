'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { ArrowRight } from 'lucide-react';
import { useLiveQuery } from 'dexie-react-hooks';
import { listRoutines } from '@/lib/repositories/routines';
import { startSession, getLastRoutineSession } from '@/lib/repositories/workouts';
import { getNextRoutineId } from '@/lib/routine-rotation';
import { useSuggestNextRoutine } from '@/lib/settings';
import { formatHaceDias } from '@/lib/fecha';
import { Button } from '@/components/ui/button';
import { GymPicker } from '@/components/gym-picker';

type Pendiente = { tipo: 'libre' } | { tipo: 'rutina'; routineId: string } | null;

export function StartWorkout() {
  const router = useRouter();
  const routines = useLiveQuery(() => listRoutines(), []);
  const ultima = useLiveQuery(() => getLastRoutineSession(), []);
  const [sugerir] = useSuggestNextRoutine();
  const [pendiente, setPendiente] = useState<Pendiente>(null);

  const lista = routines ?? [];
  const ultimaRutina = ultima?.routineId ? lista.find((r) => r.id === ultima.routineId) : undefined;
  const nextId = getNextRoutineId(lista, ultima?.routineId ?? null);
  const siguienteRutina = nextId ? lista.find((r) => r.id === nextId) : undefined;

  async function empezarConGym(gymId: string) {
    if (!pendiente) return;
    const s = pendiente.tipo === 'rutina'
      ? await startSession({ routineId: pendiente.routineId, gymId })
      : await startSession({ gymId });
    router.push(`/entrenar/${s.id}`);
  }

  if (pendiente) {
    return (
      <div className="space-y-4">
        <GymPicker onPick={empezarConGym} />
        <button
          className="label-mono text-[11px] text-muted-foreground underline"
          onClick={() => setPendiente(null)}
        >
          Cancelar
        </button>
      </div>
    );
  }

  const lineaUltima = ultimaRutina ? (
    <p className="label-mono text-[11px] text-muted-foreground">
      Última: {ultimaRutina.nombre} · {formatHaceDias(ultima!.fecha)}
    </p>
  ) : null;

  return (
    <div className="space-y-7">
      {sugerir && siguienteRutina ? (
        <section className="brutal-box brutal-shadow space-y-3 bg-primary/10 p-4">
          <p className="label-mono text-[11px] text-muted-foreground">Siguiente</p>
          <h2 className="text-3xl leading-none">{siguienteRutina.nombre}</h2>
          <Button
            onClick={() => setPendiente({ tipo: 'rutina', routineId: siguienteRutina.id })}
            size="lg"
            className="w-full font-[family-name:var(--font-display)] text-lg tracking-wide"
          >
            Empezar {siguienteRutina.nombre}
            <ArrowRight className="size-5" strokeWidth={3} />
          </Button>
          {lineaUltima}
        </section>
      ) : (
        lineaUltima
      )}

      <Button
        onClick={() => setPendiente({ tipo: 'libre' })}
        size="lg"
        className="w-full font-[family-name:var(--font-display)] text-xl tracking-wide"
      >
        Empezar entreno libre
        <ArrowRight className="size-5" strokeWidth={3} />
      </Button>

      {(routines ?? []).length > 0 && (
        <section className="space-y-3">
          <h2 className="label-mono text-[11px] text-muted-foreground">Desde una rutina</h2>
          <ul className="brutal-box divide-y-2 divide-foreground">
            {(routines ?? []).map((r) => (
              <li key={r.id} className="flex items-center justify-between gap-2 px-3 py-2.5">
                <span className="font-semibold">{r.nombre}</span>
                <Button size="sm" onClick={() => setPendiente({ tipo: 'rutina', routineId: r.id })}>
                  Empezar {r.nombre}
                </Button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
