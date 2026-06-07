'use client';

import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { getExerciseProgress, getExercisePRs } from '@/lib/repositories/stats';
import { ExerciseChart, type Metric } from '@/components/exercise-chart';

const METRICAS: { id: Metric; label: string }[] = [
  { id: '1rm', label: '1RM' },
  { id: 'peso', label: 'Peso máx' },
  { id: 'volumen', label: 'Volumen' },
];

export function ExerciseProgress({ exerciseId, gymId, sinceTs = 0 }: { exerciseId: string; gymId?: string; sinceTs?: number }) {
  const progreso = useLiveQuery(() => getExerciseProgress(exerciseId, gymId, sinceTs), [exerciseId, gymId, sinceTs]);
  const prs = useLiveQuery(() => getExercisePRs(exerciseId, gymId), [exerciseId, gymId]);
  const [metric, setMetric] = useState<Metric>('1rm');

  if (progreso === undefined || prs === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (prs === null) return <p className="text-muted-foreground">Sin datos todavía para este ejercicio.</p>;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-2">
        <div className="border-2 border-foreground bg-card p-3 brutal-shadow-sm">
          <p className="label-mono text-[9px] text-muted-foreground">Máx. peso</p>
          <p className="font-[family-name:var(--font-display)] text-2xl leading-none">{prs.maxPeso} kg</p>
        </div>
        <div className="border-2 border-foreground bg-card p-3 brutal-shadow-sm">
          <p className="label-mono text-[9px] text-muted-foreground">Mejor 1RM est.</p>
          <p className="font-[family-name:var(--font-display)] text-2xl leading-none">{prs.mejor1RM} kg</p>
        </div>
      </div>

      <div className="flex border-2 border-foreground">
        {METRICAS.map(({ id, label }, i) => (
          <button
            key={id}
            onClick={() => setMetric(id)}
            className={`label-mono flex-1 py-1.5 text-center text-[10px] transition-colors ${
              i > 0 ? 'border-l-2 border-foreground' : ''
            } ${metric === id ? 'bg-primary text-primary-foreground font-bold' : 'bg-card text-muted-foreground'}`}
          >
            {label}
          </button>
        ))}
      </div>

      {progreso.length > 1 ? (
        <ExerciseChart data={progreso} metric={metric} />
      ) : (
        <p className="text-muted-foreground">Necesitas al menos 2 registros para ver la evolución.</p>
      )}
    </div>
  );
}
