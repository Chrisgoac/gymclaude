'use client';

import { useParams } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { getMesocycle } from '@/lib/repositories/mesocycles';
import { listRoutinesByMesocycle } from '@/lib/repositories/routines';
import { MesocycleView } from '@/components/mesocycle-view';

export default function MesocyclePage() {
  const { id } = useParams<{ id: string }>();
  const meso = useLiveQuery(() => getMesocycle(id), [id]);
  const routines = useLiveQuery(() => listRoutinesByMesocycle(id), [id]);

  if (routines === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!meso) return <p className="text-muted-foreground">Este mesociclo no existe.</p>;
  return <MesocycleView meso={meso} routines={routines} />;
}
