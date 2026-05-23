'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { listDayExercises } from '@/lib/repositories/routines';
import { RoutineDayExerciseRow } from '@/components/routine-day-exercise-row';

function DayEditor({ dayId }: { dayId: string }) {
  const dia = useLiveQuery(() => db.routineDays.get(dayId), [dayId]);
  const ejercicios = useLiveQuery(() => listDayExercises(dayId), [dayId]);

  if (dia === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!dia || dia.deletedAt !== null) return <p>Día no encontrado.</p>;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">{dia.nombre}</h1>
        <Link href={`/rutinas/dia/${dayId}/anadir`} className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground">
          Añadir ejercicio
        </Link>
      </div>
      {(ejercicios ?? []).length === 0 && <p className="text-muted-foreground">Aún no hay ejercicios.</p>}
      <ul className="divide-y rounded-md border">
        {(ejercicios ?? []).map((re) => (
          <RoutineDayExerciseRow key={re.id} routineExercise={re} />
        ))}
      </ul>
      <Link href={`/rutinas/${dia.routineId}`} className="text-primary underline">
        Volver a la rutina
      </Link>
    </div>
  );
}

export default function DayEditorPage({ params }: { params: Promise<{ dayId: string }> }) {
  const [dayId, setDayId] = useState<string | null>(null);

  useEffect(() => {
    params.then(({ dayId }) => setDayId(dayId));
  }, [params]);

  if (dayId === null) return <p className="text-muted-foreground">Cargando…</p>;
  return <DayEditor dayId={dayId} />;
}
