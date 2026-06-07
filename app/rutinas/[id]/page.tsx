'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { listRoutineExercises, softDeleteRoutine } from '@/lib/repositories/routines';
import { RoutineDayExerciseRow } from '@/components/routine-day-exercise-row';
import { Button } from '@/components/ui/button';
import { useDialogs } from '@/components/ui/dialog-provider';

function RoutineEditor({ id }: { id: string }) {
  const router = useRouter();
  const { confirm } = useDialogs();
  const routine = useLiveQuery(() => db.routines.get(id), [id]);
  const ejercicios = useLiveQuery(() => listRoutineExercises(id), [id]);

  if (routine === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!routine || routine.deletedAt !== null) return <p>Rutina no encontrada.</p>;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{routine.nombre}</h1>
        {routine.descripcion && <p className="text-sm text-muted-foreground">{routine.descripcion}</p>}
      </div>

      <section className="space-y-3">
        <h2 className="label-mono text-[11px] text-muted-foreground">Ejercicios</h2>
        {(ejercicios ?? []).length === 0 && (
          <p className="label-mono text-xs text-muted-foreground">Aún no hay ejercicios.</p>
        )}
        <ul className="brutal-box divide-y-2 divide-foreground">
          {(ejercicios ?? []).map((re) => (
            <RoutineDayExerciseRow key={re.id} routineExercise={re} />
          ))}
        </ul>
        <Link
          href={`/rutinas/${id}/anadir`}
          className="label-mono block border-2 border-dashed border-foreground bg-card/50 p-4 text-center text-xs text-foreground transition-colors hover:bg-card"
        >
          + Añadir ejercicio
        </Link>
      </section>

      <Button
        variant="destructive"
        className="w-full"
        onClick={async () => {
          if (await confirm({ titulo: `¿Borrar la rutina "${routine.nombre}"?`, confirmar: 'Borrar', destructivo: true })) {
            await softDeleteRoutine(id);
            router.push('/rutinas');
          }
        }}
      >
        Borrar rutina
      </Button>
    </div>
  );
}

export default function RoutineEditorPage({ params }: { params: Promise<{ id: string }> }) {
  const [id, setId] = useState<string | null>(null);

  useEffect(() => {
    params.then(({ id }) => setId(id));
  }, [params]);

  if (id === null) return <p className="text-muted-foreground">Cargando…</p>;
  return <RoutineEditor id={id} />;
}
