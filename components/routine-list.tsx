'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { listRoutines } from '@/lib/repositories/routines';

export function RoutineList() {
  const routines = useLiveQuery(() => listRoutines(), []);

  if (routines === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (routines.length === 0) return <p className="text-muted-foreground">Aún no tienes rutinas.</p>;

  return (
    <ul className="divide-y rounded-md border">
      {routines.map((r) => (
        <li key={r.id}>
          <Link href={`/rutinas/${r.id}`} className="block p-3">
            <span className="font-medium">{r.nombre}</span>
            {r.descripcion && <span className="block text-xs text-muted-foreground">{r.descripcion}</span>}
          </Link>
        </li>
      ))}
    </ul>
  );
}
