'use client';

import { useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { listExercises } from '@/lib/repositories/exercises';
import { addLoggedExercise } from '@/lib/repositories/workouts';
import { equipmentLabel } from '@/lib/labels';
import { Input } from '@/components/ui/input';

export default function AnadirEjercicioEntrenoPage() {
  const params = useParams<{ sessionId: string }>();
  const sessionId = params.sessionId;
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
            <button
              className="flex w-full items-center justify-between p-3 text-left"
              onClick={async () => {
                await addLoggedExercise(sessionId, e.id);
                router.push(`/entrenar/${sessionId}`);
              }}
            >
              <span>{e.nombre}</span>
              <span className="text-xs text-muted-foreground">{equipmentLabel[e.equipamiento]}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
