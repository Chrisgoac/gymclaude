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
    <div className="brutal-box">
      <div className="flex items-center justify-between gap-2 border-b-2 border-foreground bg-foreground px-3 py-2">
        <span className="font-[family-name:var(--font-display)] text-lg uppercase leading-none tracking-wide text-background">
          {ejercicio?.nombre ?? '—'}
        </span>
        <button
          className="label-mono text-[10px] text-background/70 hover:text-destructive"
          onClick={() => softDeleteLoggedExercise(loggedExercise.id)}
        >
          Quitar
        </button>
      </div>

      <ul className="divide-y-2 divide-foreground">
        {(sets ?? []).map((set, i) => (
          <li key={set.id} className="flex items-end gap-2 px-3 py-3">
            <span className="grid h-11 w-7 shrink-0 place-items-center border-2 border-foreground bg-secondary font-[family-name:var(--font-display)] text-base text-secondary-foreground">
              {i + 1}
            </span>
            <div className="flex-1 space-y-1">
              <div className="flex items-baseline justify-between">
                <Label htmlFor={`peso-${set.id}`}>Peso</Label>
                <span className="label-mono text-[9px] text-muted-foreground">kg</span>
              </div>
              <Input
                id={`peso-${set.id}`}
                inputMode="decimal"
                defaultValue={set.peso}
                className="h-14 text-center text-3xl font-[family-name:var(--font-display)] tabular-nums"
                onChange={(e) => updateSet(set.id, { peso: parseNum(e.target.value) })}
              />
            </div>
            <span className="self-center pb-5 font-[family-name:var(--font-display)] text-2xl text-muted-foreground">
              ×
            </span>
            <div className="flex-1 space-y-1">
              <Label htmlFor={`reps-${set.id}`}>Reps</Label>
              <Input
                id={`reps-${set.id}`}
                inputMode="numeric"
                defaultValue={set.reps}
                className="h-14 text-center text-3xl font-[family-name:var(--font-display)] tabular-nums"
                onChange={(e) => updateSet(set.id, { reps: parseNum(e.target.value) })}
              />
            </div>
            <button
              className="grid h-11 w-7 shrink-0 place-items-center text-muted-foreground hover:text-destructive"
              aria-label="Eliminar serie"
              onClick={() => softDeleteSet(set.id)}
            >
              ✕
            </button>
          </li>
        ))}
      </ul>

      <div className="border-t-2 border-foreground p-3">
        <Button type="button" variant="outline" className="w-full" onClick={añadirSerie}>
          Añadir serie
        </Button>
      </div>
    </div>
  );
}
