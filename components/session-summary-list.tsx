'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { listSessionSummaries } from '@/lib/repositories/stats';

export function SessionSummaryList() {
  const resumenes = useLiveQuery(() => listSessionSummaries(), []);

  if (resumenes === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (resumenes.length === 0) return <p className="text-muted-foreground">Aún no has registrado entrenos.</p>;

  return (
    <ul className="divide-y rounded-md border">
      {resumenes.map(({ session, numEjercicios, volumen }) => (
        <li key={session.id}>
          <Link href={`/historial/${session.id}`} className="flex items-center justify-between p-3">
            <div>
              <span className="font-medium">{new Date(session.fecha).toLocaleDateString('es-ES')}</span>
              <span className="block text-xs text-muted-foreground">{numEjercicios} ejercicios</span>
            </div>
            <span className="text-sm text-muted-foreground">{volumen} kg·rep</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
