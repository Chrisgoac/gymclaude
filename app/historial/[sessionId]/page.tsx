'use client';

import { useParams, useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { getSession, listSessionExercises, softDeleteSession } from '@/lib/repositories/workouts';
import { listGyms, getGymsMap, gymDisplayName } from '@/lib/repositories/gyms';
import { Button } from '@/components/ui/button';
import { useDialogs } from '@/components/ui/dialog-provider';

function ExerciseDetail({ loggedExerciseId, exerciseId }: { loggedExerciseId: string; exerciseId: string }) {
  const ejercicio = useLiveQuery(() => db.exercises.get(exerciseId), [exerciseId]);
  const sets = useLiveQuery(
    async () =>
      (await db.loggedSets.where('loggedExerciseId').equals(loggedExerciseId).toArray())
        .filter((s) => s.deletedAt === null)
        .sort((a, b) => a.orden - b.orden),
    [loggedExerciseId],
  );
  return (
    <div className="rounded-md border p-3">
      <p className="font-medium">{ejercicio?.nombre ?? '—'}</p>
      <ul className="mt-1 text-sm text-muted-foreground">
        {(sets ?? []).map((s, i) => (
          <li key={s.id}>
            {i + 1}. {s.peso} kg × {s.reps}
          </li>
        ))}
      </ul>
    </div>
  );
}

export default function SessionDetailPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const router = useRouter();
  const { confirm } = useDialogs();
  const session = useLiveQuery(() => getSession(sessionId), [sessionId]);
  const ejercicios = useLiveQuery(() => listSessionExercises(sessionId), [sessionId]);
  const gyms = useLiveQuery(() => listGyms(), []);
  const gymsMap = useLiveQuery(() => getGymsMap(), []);

  if (session === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!session || session.deletedAt !== null) return <p>Entreno no encontrado.</p>;

  // Si el gimnasio del entreno está archivado/borrado no aparece en la lista de activos;
  // lo añadimos como opción extra para no perder ni tergiversar su asignación real.
  const activos = gyms ?? [];
  const actualFueraDeLista = !!session.gymId && !activos.some((g) => g.id === session.gymId);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{new Date(session.fecha).toLocaleDateString('es-ES')}</h1>
      <div className="flex items-center gap-2">
        <span className="label-mono text-[11px] text-muted-foreground">Gimnasio:</span>
        <select
          className="h-9 border-2 border-input bg-card px-2 text-sm font-medium"
          value={session.gymId ?? ''}
          onChange={async (e) => {
            const v = e.target.value || null;
            await db.workoutSessions.update(session.id, { gymId: v, updatedAt: Date.now() });
          }}
        >
          <option value="">Sin gimnasio</option>
          {actualFueraDeLista && session.gymId && (
            <option value={session.gymId}>{gymDisplayName(session.gymId, gymsMap ?? new Map())}</option>
          )}
          {activos.map((g) => (
            <option key={g.id} value={g.id}>{g.nombre}</option>
          ))}
        </select>
      </div>
      <div className="space-y-3">
        {(ejercicios ?? []).map((le) => (
          <ExerciseDetail key={le.id} loggedExerciseId={le.id} exerciseId={le.exerciseId} />
        ))}
      </div>
      <Button
        variant="destructive"
        onClick={async () => {
          if (await confirm({ titulo: '¿Borrar este entreno?', confirmar: 'Borrar', destructivo: true })) {
            await softDeleteSession(sessionId);
            router.push('/historial');
          }
        }}
      >
        Borrar entreno
      </Button>
    </div>
  );
}
