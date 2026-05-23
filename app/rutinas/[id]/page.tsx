'use client';

import { useState, useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { addDay, listDays, softDeleteDay, softDeleteRoutine } from '@/lib/repositories/routines';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';

function RoutineEditor({ id }: { id: string }) {
  const router = useRouter();
  const [nuevoDia, setNuevoDia] = useState('');

  const routine = useLiveQuery(() => db.routines.get(id), [id]);
  const dias = useLiveQuery(() => listDays(id), [id]);

  if (routine === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!routine || routine.deletedAt !== null) return <p>Rutina no encontrada.</p>;

  async function añadir() {
    if (nuevoDia.trim() === '') return;
    await addDay(id, { nombre: nuevoDia.trim() });
    setNuevoDia('');
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">{routine.nombre}</h1>
        {routine.descripcion && <p className="text-sm text-muted-foreground">{routine.descripcion}</p>}
      </div>

      <section className="space-y-2">
        <h2 className="text-sm font-semibold uppercase text-muted-foreground">Días</h2>
        {(dias ?? []).length === 0 && <p className="text-muted-foreground">Aún no hay días.</p>}
        <ul className="divide-y rounded-md border">
          {(dias ?? []).map((d) => (
            <li key={d.id} className="flex items-center justify-between p-3">
              <Link href={`/rutinas/dia/${d.id}`} className="font-medium">
                {d.nombre}
              </Link>
              <button
                className="text-xs text-destructive"
                onClick={async () => {
                  if (window.confirm(`¿Borrar el día "${d.nombre}"?`)) await softDeleteDay(d.id);
                }}
              >
                Borrar
              </button>
            </li>
          ))}
        </ul>
        <div className="flex gap-2">
          <Input placeholder="Nombre del día" value={nuevoDia} onChange={(e) => setNuevoDia(e.target.value)} />
          <Button type="button" onClick={añadir}>
            Añadir día
          </Button>
        </div>
      </section>

      <Button
        variant="destructive"
        onClick={async () => {
          if (window.confirm(`¿Borrar la rutina "${routine.nombre}" y todos sus días?`)) {
            await softDeleteRoutine(id);
            router.push('/rutinas');
          }
        }}
      >
        Borrar rutina
      </Button>
    </div>
  );
}

export default function RoutineEditorPage({ params }: { params: Promise<{ id: string }> }) {
  const [id, setId] = useState<string | null>(null);

  useEffect(() => {
    params.then(({ id }) => setId(id));
  }, [params]);

  if (id === null) return <p className="text-muted-foreground">Cargando…</p>;
  return <RoutineEditor id={id} />;
}
