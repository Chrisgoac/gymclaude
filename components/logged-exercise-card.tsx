'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import type { LoggedExercise } from '@/lib/db/types';
import {
  addSet, updateSet, softDeleteSet, listExerciseSets, getLastSet, softDeleteLoggedExercise,
} from '@/lib/repositories/workouts';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';

function parseNum(v: string): number {
  const n = Number(v);
  return v.trim() === '' || Number.isNaN(n) ? 0 : n;
}

export function LoggedExerciseCard({
  loggedExercise,
  sessionId,
}: {
  loggedExercise: LoggedExercise;
  sessionId: string;
}) {
  const ejercicio = useLiveQuery(() => db.exercises.get(loggedExercise.exerciseId), [loggedExercise.exerciseId]);
  const sets = useLiveQuery(() => listExerciseSets(loggedExercise.id), [loggedExercise.id]);

  async function añadirSerie() {
    const actuales = sets ?? [];
    if (actuales.length > 0) {
      const ultima = actuales[actuales.length - 1];
      await addSet(loggedExercise.id, { peso: ultima.peso, reps: ultima.reps });
      return;
    }
    const previa = await getLastSet(loggedExercise.exerciseId, sessionId);
    await addSet(loggedExercise.id, { peso: previa?.peso ?? 0, reps: previa?.reps ?? 0 });
  }

  return (
    <div className="space-y-3 rounded-md border p-3">
      <div className="flex items-center justify-between">
        <span className="font-medium">{ejercicio?.nombre ?? '—'}</span>
        <button className="text-xs text-destructive" onClick={() => softDeleteLoggedExercise(loggedExercise.id)}>
          Quitar
        </button>
      </div>

      <ul className="space-y-2">
        {(sets ?? []).map((set, i) => (
          <li key={set.id} className="flex items-end gap-2">
            <span className="w-5 pb-2 text-xs text-muted-foreground">{i + 1}</span>
            <div className="flex-1 space-y-1">
              <Label htmlFor={`peso-${set.id}`} className="text-xs">Peso</Label>
              <Input
                id={`peso-${set.id}`}
                inputMode="decimal"
                defaultValue={set.peso}
                onChange={(e) => updateSet(set.id, { peso: parseNum(e.target.value) })}
              />
            </div>
            <div className="flex-1 space-y-1">
              <Label htmlFor={`reps-${set.id}`} className="text-xs">Reps</Label>
              <Input
                id={`reps-${set.id}`}
                inputMode="numeric"
                defaultValue={set.reps}
                onChange={(e) => updateSet(set.id, { reps: parseNum(e.target.value) })}
              />
            </div>
            <button className="pb-2 text-xs text-destructive" onClick={() => softDeleteSet(set.id)}>
              ✕
            </button>
          </li>
        ))}
      </ul>

      <Button type="button" variant="secondary" className="w-full" onClick={añadirSerie}>
        Añadir serie
      </Button>
    </div>
  );
}
