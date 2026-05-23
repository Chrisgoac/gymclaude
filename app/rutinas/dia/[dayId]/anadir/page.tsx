'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { listExercises } from '@/lib/repositories/exercises';
import { addExerciseToDay } from '@/lib/repositories/routines';
import { equipmentLabel } from '@/lib/labels';
import { Input } from '@/components/ui/input';

function AnadirEjercicio({ dayId }: { dayId: string }) {
  const router = useRouter();
  const [query, setQuery] = useState('');
  const ejercicios = useLiveQuery(() => listExercises(), []);

  const filtrados = (ejercicios ?? []).filter((e) =>
    e.nombre.toLowerCase().includes(query.trim().toLowerCase()),
  );

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Añadir ejercicio</h1>
      <Input placeholder="Buscar ejercicio…" value={query} onChange={(e) => setQuery(e.target.value)} />
      <ul className="divide-y rounded-md border">
        {filtrados.map((e) => (
          <li key={e.id}>
            <button className="flex w-full items-center justify-between p-3 text-left"
              onClick={async () => {
                await addExerciseToDay(dayId, { exerciseId: e.id });
                router.push(`/rutinas/dia/${dayId}`);
              }}>
              <span>{e.nombre}</span>
              <span className="text-xs text-muted-foreground">{equipmentLabel[e.equipamiento]}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function AnadirEjercicioPage({ params }: { params: Promise<{ dayId: string }> }) {
  const [dayId, setDayId] = useState<string | null>(null);

  useEffect(() => {
    params.then(({ dayId }) => setDayId(dayId));
  }, [params]);

  if (dayId === null) return <p className="text-muted-foreground">Cargando…</p>;
  return <AnadirEjercicio dayId={dayId} />;
}
