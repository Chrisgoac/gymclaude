'use client';

import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import type { LoggedExercise } from '@/lib/db/types';
import {
  addSet, updateSet, softDeleteSet, listExerciseSets, getLastWorkingSets, getLastPerformance,
  softDeleteLoggedExercise,
} from '@/lib/repositories/workouts';
import { getRoutineExerciseTarget } from '@/lib/repositories/routines';
import { getPhoto } from '@/lib/repositories/exercise-photos';
import { resolveExercisePhotoUrl } from '@/lib/catalog-photos';
import { formatHaceDias } from '@/lib/fecha';
import { calcularSugerencia, describeMotivo, inferirSalto } from '@/lib/progresion';
import { getExerciseProgress } from '@/lib/repositories/stats';
import { detectarEstancamiento, DELOAD_CONSEJO } from '@/lib/insights';
import { useModoProgresion, useIncrementos } from '@/lib/settings';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { RestTimer } from '@/components/rest-timer';

import { parseEntero, parseDecimal } from '@/lib/num';

export function LoggedExerciseCard({
  loggedExercise,
  sessionId,
  gymId,
  routineId,
}: {
  loggedExercise: LoggedExercise;
  sessionId: string;
  gymId?: string;
  routineId?: string;
}) {
  const ejercicio = useLiveQuery(() => db.exercises.get(loggedExercise.exerciseId), [loggedExercise.exerciseId]);
  const sets = useLiveQuery(() => listExerciseSets(loggedExercise.id), [loggedExercise.id]);
  const foto = useLiveQuery(() => getPhoto(loggedExercise.exerciseId), [loggedExercise.exerciseId]);
  const ultima = useLiveQuery(
    () => getLastPerformance(loggedExercise.exerciseId, sessionId, gymId),
    [loggedExercise.exerciseId, sessionId, gymId],
  );
  const objetivo = useLiveQuery(
    () => (routineId ? getRoutineExerciseTarget(routineId, loggedExercise.exerciseId) : undefined),
    [routineId, loggedExercise.exerciseId],
  );
  const estancado = useLiveQuery(async () => {
    const points = await getExerciseProgress(loggedExercise.exerciseId, gymId, 0, sessionId);
    return detectarEstancamiento(points).estancado;
  }, [loggedExercise.exerciseId, gymId, sessionId]);
  const [restKey, setRestKey] = useState(0);
  const [modo] = useModoProgresion();
  const [incrementos] = useIncrementos();

  const sugerencia = useLiveQuery(async () => {
    if (!ejercicio) return undefined;
    const ultimo = await getLastWorkingSets(loggedExercise.exerciseId, sessionId, gymId);
    const salto = inferirSalto((ultimo ?? []).map((s) => s.peso), {
      equipamiento: ejercicio.equipamiento,
      defaults: incrementos,
      override: ejercicio.incrementoKg,
    });
    const sug = calcularSugerencia({
      modo,
      ultimo,
      objetivo: routineId ? objetivo ?? undefined : undefined,
      salto,
      esCorporal: ejercicio.equipamiento === 'peso_corporal',
    });
    return { sug, badge: describeMotivo(sug, salto) };
  }, [ejercicio, loggedExercise.exerciseId, sessionId, gymId, routineId, objetivo, modo, incrementos]);

  async function añadirSerie() {
    const actuales = sets ?? [];
    if (actuales.length > 0) {
      const ultimaSerie = actuales[actuales.length - 1];
      await addSet(loggedExercise.id, { peso: ultimaSerie.peso, reps: ultimaSerie.reps });
    } else {
      const sug = sugerencia?.sug;
      await addSet(loggedExercise.id, { peso: sug?.pesoSugerido ?? 0, reps: sug?.repsSugeridas ?? 0 });
    }
    setRestKey((k) => k + 1);
  }

  return (
    <div className="brutal-box">
      <div className="flex items-center justify-between gap-2 border-b-2 border-foreground bg-foreground px-3 py-2">
        <span className="flex items-center gap-2">
          {(() => {
            const url = resolveExercisePhotoUrl(loggedExercise.exerciseId, foto?.url);
            return url ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={url} alt="" className="size-7 shrink-0 border border-background object-cover" />
            ) : null;
          })()}
          <span className="font-[family-name:var(--font-display)] text-lg uppercase leading-none tracking-wide text-background">
            {ejercicio?.nombre ?? '—'}
          </span>
        </span>
        <button
          className="label-mono text-[10px] text-background/70 hover:text-destructive"
          onClick={() => softDeleteLoggedExercise(loggedExercise.id)}
        >
          Quitar
        </button>
      </div>

      {(ultima || objetivo || sugerencia?.badge || estancado) && (
        <div className="space-y-0.5 border-b-2 border-foreground bg-card px-3 py-2">
          {ultima && (
            <p className="label-mono text-[10px] text-muted-foreground">
              ÚLTIMA VEZ · {ultima.peso} × {ultima.reps} · {formatHaceDias(ultima.fecha)}
            </p>
          )}
          {objetivo && (objetivo.seriesObjetivo || objetivo.repsObjetivo || objetivo.descansoSegundos) && (
            <p className="label-mono text-[10px] text-muted-foreground">
              OBJETIVO
              {objetivo.seriesObjetivo || objetivo.repsObjetivo
                ? ` · ${objetivo.seriesObjetivo ?? '—'} × ${objetivo.repsObjetivo ?? '—'}`
                : ''}
              {objetivo.descansoSegundos ? ` · desc. ${objetivo.descansoSegundos}s` : ''}
            </p>
          )}
          {sugerencia?.badge && (
            <p className="label-mono text-[10px] font-semibold text-primary">
              {sugerencia.badge}
            </p>
          )}
          {estancado && (
            <p className="label-mono text-[10px] font-semibold text-destructive" title={DELOAD_CONSEJO}>
              ESTANCADO · {DELOAD_CONSEJO}
            </p>
          )}
        </div>
      )}

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
                onChange={(e) => updateSet(set.id, { peso: parseDecimal(e.target.value) })}
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
                onChange={(e) => updateSet(set.id, { reps: parseEntero(e.target.value) })}
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

      <div className="space-y-2 border-t-2 border-foreground p-3">
        <RestTimer key={restKey} startKey={restKey} targetSeconds={objetivo?.descansoSegundos} />
        <Button type="button" variant="outline" className="w-full" onClick={añadirSerie}>
          Añadir serie
        </Button>
      </div>
    </div>
  );
}
