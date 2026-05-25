'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import type { RoutineExercise } from '@/lib/db/types';
import { updateRoutineExercise, softDeleteRoutineExercise } from '@/lib/repositories/routines';
import { getPhoto } from '@/lib/repositories/exercise-photos';
import { resolveExercisePhotoUrl } from '@/lib/catalog-photos';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';

function parseNum(v: string): number | undefined {
  const n = Number(v);
  return v.trim() === '' || Number.isNaN(n) ? undefined : n;
}

export function RoutineDayExerciseRow({ routineExercise }: { routineExercise: RoutineExercise }) {
  const ejercicio = useLiveQuery(() => db.exercises.get(routineExercise.exerciseId), [routineExercise.exerciseId]);
  const foto = useLiveQuery(() => getPhoto(routineExercise.exerciseId), [routineExercise.exerciseId]);
  const fotoUrl = resolveExercisePhotoUrl(routineExercise.exerciseId, foto?.url);

  return (
    <li className="space-y-2 p-3">
      <div className="flex items-center justify-between">
        <span className="flex items-center gap-2">
          {fotoUrl && (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={fotoUrl} alt="" className="size-8 shrink-0 border-2 border-foreground object-cover" />
          )}
          <span className="font-medium">{ejercicio?.nombre ?? '—'}</span>
        </span>
        <button className="text-xs text-destructive" onClick={() => softDeleteRoutineExercise(routineExercise.id)}>
          Quitar
        </button>
      </div>
      <div className="grid grid-cols-3 gap-2">
        <div className="space-y-1">
          <Label htmlFor={`series-${routineExercise.id}`} className="text-xs">Series</Label>
          <Input id={`series-${routineExercise.id}`} inputMode="numeric" defaultValue={routineExercise.seriesObjetivo ?? ''}
            onChange={(e) => updateRoutineExercise(routineExercise.id, { seriesObjetivo: parseNum(e.target.value) })} />
        </div>
        <div className="space-y-1">
          <Label htmlFor={`reps-${routineExercise.id}`} className="text-xs">Reps</Label>
          <Input id={`reps-${routineExercise.id}`} inputMode="numeric" defaultValue={routineExercise.repsObjetivo ?? ''}
            onChange={(e) => updateRoutineExercise(routineExercise.id, { repsObjetivo: parseNum(e.target.value) })} />
        </div>
        <div className="space-y-1">
          <Label htmlFor={`descanso-${routineExercise.id}`} className="text-xs">Descanso (s)</Label>
          <Input id={`descanso-${routineExercise.id}`} inputMode="numeric" defaultValue={routineExercise.descansoSegundos ?? ''}
            onChange={(e) => updateRoutineExercise(routineExercise.id, { descansoSegundos: parseNum(e.target.value) })} />
        </div>
      </div>
    </li>
  );
}
