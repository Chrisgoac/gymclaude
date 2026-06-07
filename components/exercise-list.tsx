'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import type { Exercise, MuscleGroup, Equipment } from '@/lib/db/types';
import { MUSCLE_GROUPS, EQUIPMENTS } from '@/lib/db/types';
import { listExercises } from '@/lib/repositories/exercises';
import { getPhotosMap } from '@/lib/repositories/exercise-photos';
import { resolveExercisePhotoUrl } from '@/lib/catalog-photos';
import { muscleGroupLabel, equipmentLabel } from '@/lib/labels';
import { Input } from '@/components/ui/input';

export function ExerciseList() {
  const [query, setQuery] = useState('');
  const [grupo, setGrupo] = useState<MuscleGroup | null>(null);
  const [equipo, setEquipo] = useState<Equipment | null>(null);

  const exercises = useLiveQuery(() => listExercises(), []);
  const fotos = useLiveQuery(() => getPhotosMap(), []);

  const gruposPresentes = useMemo(() => {
    const s = new Set<MuscleGroup>();
    for (const e of exercises ?? []) s.add(e.grupoMuscular);
    return MUSCLE_GROUPS.filter((g) => s.has(g));
  }, [exercises]);

  const equiposPresentes = useMemo(() => {
    const s = new Set<Equipment>();
    for (const e of exercises ?? []) s.add(e.equipamiento);
    return EQUIPMENTS.filter((eq) => s.has(eq));
  }, [exercises]);

  const grouped = useMemo(() => {
    const list = (exercises ?? []).filter((e) => {
      if (!e.nombre.toLowerCase().includes(query.trim().toLowerCase())) return false;
      if (grupo && e.grupoMuscular !== grupo) return false;
      if (equipo && e.equipamiento !== equipo) return false;
      return true;
    });
    const map = new Map<MuscleGroup, Exercise[]>();
    for (const ex of list) {
      const arr = map.get(ex.grupoMuscular) ?? [];
      arr.push(ex);
      map.set(ex.grupoMuscular, arr);
    }
    return MUSCLE_GROUPS.filter((g) => map.has(g)).map((g) => ({ grupo: g, items: map.get(g)! }));
  }, [exercises, query, grupo, equipo]);

  if (exercises === undefined) return <p className="text-muted-foreground">Cargando…</p>;

  return (
    <div className="space-y-4">
      <Input placeholder="Buscar ejercicio…" value={query} onChange={(e) => setQuery(e.target.value)} />

      <div className="flex flex-wrap gap-2">
        {gruposPresentes.map((g) => {
          const activo = grupo === g;
          return (
            <button
              key={g}
              onClick={() => setGrupo(activo ? null : g)}
              className={`label-mono border-2 border-foreground px-2 py-1 text-[10px] ${
                activo ? 'bg-primary text-primary-foreground brutal-shadow-sm' : 'bg-card text-muted-foreground'
              }`}
            >
              {muscleGroupLabel[g]}
            </button>
          );
        })}
      </div>

      <div className="flex flex-wrap gap-2">
        {equiposPresentes.map((eq) => {
          const activo = equipo === eq;
          return (
            <button
              key={eq}
              onClick={() => setEquipo(activo ? null : eq)}
              className={`label-mono border-2 border-foreground px-2 py-1 text-[10px] ${
                activo ? 'bg-foreground text-background brutal-shadow-sm' : 'bg-card text-muted-foreground'
              }`}
            >
              {equipmentLabel[eq]}
            </button>
          );
        })}
      </div>

      {grouped.length === 0 && <p className="text-muted-foreground">No hay ejercicios.</p>}
      {grouped.map(({ grupo: g, items }) => (
        <section key={g} className="space-y-1">
          <h2 className="label-mono text-[11px] text-muted-foreground">{muscleGroupLabel[g]}</h2>
          <ul className="brutal-box divide-y-2 divide-foreground">
            {items.map((ex) => (
              <li key={ex.id}>
                <Link href={`/ejercicios/${ex.id}`} className="flex items-center gap-3 p-2">
                  {(() => {
                    const url = resolveExercisePhotoUrl(ex.id, fotos?.get(ex.id)?.url);
                    return url ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={url} alt="" className="size-12 shrink-0 border-2 border-foreground object-cover" />
                    ) : (
                      <span className="size-12 shrink-0 border-2 border-foreground bg-card/50" aria-hidden="true" />
                    );
                  })()}
                  <span className="flex-1 font-medium">{ex.nombre}</span>
                  <span className="label-mono text-[10px] text-muted-foreground">{equipmentLabel[ex.equipamiento]}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
