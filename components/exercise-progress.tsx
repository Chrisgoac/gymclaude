'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { getExerciseProgress, getExercisePRs } from '@/lib/repositories/stats';
import { ExerciseChart } from '@/components/exercise-chart';

export function ExerciseProgress({ exerciseId }: { exerciseId: string }) {
  const progreso = useLiveQuery(() => getExerciseProgress(exerciseId), [exerciseId]);
  const prs = useLiveQuery(() => getExercisePRs(exerciseId), [exerciseId]);

  if (progreso === undefined || prs === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (prs === null) return <p className="text-muted-foreground">Sin datos todavía para este ejercicio.</p>;

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-2">
        <div className="rounded-md border p-3">
          <p className="text-xs text-muted-foreground">Máx. peso</p>
          <p className="text-lg font-bold">{prs.maxPeso} kg</p>
        </div>
        <div className="rounded-md border p-3">
          <p className="text-xs text-muted-foreground">Mejor 1RM est.</p>
          <p className="text-lg font-bold">{prs.mejor1RM} kg</p>
        </div>
      </div>
      {progreso.length > 1 && <ExerciseChart data={progreso} />}
    </div>
  );
}
