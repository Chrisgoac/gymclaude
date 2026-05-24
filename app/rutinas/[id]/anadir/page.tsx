'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { listExercises } from '@/lib/repositories/exercises';
import { addExerciseToRoutine } from '@/lib/repositories/routines';
import { equipmentLabel } from '@/lib/labels';
import { Input } from '@/components/ui/input';

function AnadirEjercicio({ routineId }: { routineId: string }) {
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
      <ul className="brutal-box divide-y-2 divide-foreground">
        {filtrados.map((e) => (
          <li key={e.id}>
            <button
              className="flex w-full items-center justify-between p-3 text-left"
              onClick={async () => {
                await addExerciseToRoutine(routineId, { exerciseId: e.id });
                router.push(`/rutinas/${routineId}`);
              }}
            >
              <span className="font-medium">{e.nombre}</span>
              <span className="label-mono text-[10px] text-muted-foreground">{equipmentLabel[e.equipamiento]}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function AnadirEjercicioPage({ params }: { params: Promise<{ id: string }> }) {
  const [routineId, setRoutineId] = useState<string | null>(null);

  useEffect(() => {
    params.then(({ id }) => setRoutineId(id));
  }, [params]);

  if (routineId === null) return <p className="text-muted-foreground">Cargando…</p>;
  return <AnadirEjercicio routineId={routineId} />;
}
