'use client';

import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { listExercises } from '@/lib/repositories/exercises';
import { getVolumeByMuscle } from '@/lib/repositories/stats';
import { muscleGroupLabel } from '@/lib/labels';
import { ExerciseProgress } from '@/components/exercise-progress';

export default function ProgresoPage() {
  const ejercicios = useLiveQuery(() => listExercises(), []);
  const volumen = useLiveQuery(() => getVolumeByMuscle(), []);
  const [seleccion, setSeleccion] = useState('');

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Progreso</h1>

      <section className="space-y-2">
        <label htmlFor="ejercicio" className="text-sm font-semibold uppercase text-muted-foreground">
          Por ejercicio
        </label>
        <select
          id="ejercicio"
          className="w-full rounded-md border p-2"
          value={seleccion}
          onChange={(e) => setSeleccion(e.target.value)}
        >
          <option value="">Elige un ejercicio…</option>
          {(ejercicios ?? []).map((e) => (
            <option key={e.id} value={e.id}>
              {e.nombre}
            </option>
          ))}
        </select>
        {seleccion && <ExerciseProgress exerciseId={seleccion} />}
      </section>

      <section className="space-y-2">
        <h2 className="text-sm font-semibold uppercase text-muted-foreground">Volumen por grupo muscular</h2>
        {(volumen ?? []).length === 0 && <p className="text-muted-foreground">Aún no hay volumen registrado.</p>}
        <ul className="space-y-1">
          {(volumen ?? []).map((v) => (
            <li key={v.grupo} className="flex items-center justify-between text-sm">
              <span>{muscleGroupLabel[v.grupo]}</span>
              <span className="text-muted-foreground">{v.volumen} kg·rep</span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}
