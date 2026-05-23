'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import type { Exercise, MuscleGroup } from '@/lib/db/types';
import { MUSCLE_GROUPS } from '@/lib/db/types';
import { muscleGroupLabel, equipmentLabel } from '@/lib/labels';
import { Input } from '@/components/ui/input';

export function ExerciseList() {
  const [query, setQuery] = useState('');

  const exercises = useLiveQuery(async () => {
    const all = await db.exercises.toArray();
    return all
      .filter((e) => e.deletedAt === null)
      .sort((a, b) => a.nombre.localeCompare(b.nombre, 'es', { sensitivity: 'base' }));
  }, []);

  const grouped = useMemo(() => {
    const list = (exercises ?? []).filter((e) =>
      e.nombre.toLowerCase().includes(query.trim().toLowerCase()),
    );
    const map = new Map<MuscleGroup, Exercise[]>();
    for (const ex of list) {
      const arr = map.get(ex.grupoMuscular) ?? [];
      arr.push(ex);
      map.set(ex.grupoMuscular, arr);
    }
    return MUSCLE_GROUPS.filter((g) => map.has(g)).map((g) => ({
      grupo: g,
      items: map.get(g)!,
    }));
  }, [exercises, query]);

  if (exercises === undefined) {
    return <p className="text-muted-foreground">Cargando…</p>;
  }

  return (
    <div className="space-y-4">
      <Input
        placeholder="Buscar ejercicio…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      {grouped.length === 0 && <p className="text-muted-foreground">No hay ejercicios.</p>}
      {grouped.map(({ grupo, items }) => (
        <section key={grupo} className="space-y-1">
          <h2 className="text-xs font-semibold uppercase text-muted-foreground">
            {muscleGroupLabel[grupo]}
          </h2>
          <ul className="divide-y rounded-md border">
            {items.map((ex) => (
              <li key={ex.id}>
                <Link href={`/ejercicios/${ex.id}`} className="flex items-center justify-between p-3">
                  <span>{ex.nombre}</span>
                  <span className="text-xs text-muted-foreground">{equipmentLabel[ex.equipamiento]}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
