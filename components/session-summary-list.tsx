'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { listSessionSummaries } from '@/lib/repositories/stats';
import { getGymsMap, gymDisplayName } from '@/lib/repositories/gyms';
import { useGymFilter, filtroAGymId } from '@/lib/gym-filter';

export function SessionSummaryList() {
  const [filtro] = useGymFilter();
  const gymId = filtroAGymId(filtro);
  const resumenes = useLiveQuery(() => listSessionSummaries(gymId), [gymId]);
  const gymsMap = useLiveQuery(() => getGymsMap(), []);

  if (resumenes === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (resumenes.length === 0) return <p className="text-muted-foreground">Aún no has registrado entrenos.</p>;

  return (
    <ul className="divide-y-2 divide-foreground brutal-box">
      {resumenes.map(({ session, numEjercicios, volumen }) => (
        <li key={session.id}>
          <Link href={`/historial/${session.id}`} className="flex items-center justify-between p-3">
            <div>
              <span className="font-medium">{new Date(session.fecha).toLocaleDateString('es-ES')}</span>
              <span className="block text-xs text-muted-foreground">
                {numEjercicios} ejercicios · {gymDisplayName(session.gymId, gymsMap ?? new Map())}
              </span>
            </div>
            <span className="label-mono text-xs text-muted-foreground">{volumen} kg·rep</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
