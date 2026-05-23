'use client';

import { useParams, useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { getSession, listSessionExercises, softDeleteSession } from '@/lib/repositories/workouts';
import { Button } from '@/components/ui/button';

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
  const session = useLiveQuery(() => getSession(sessionId), [sessionId]);
  const ejercicios = useLiveQuery(() => listSessionExercises(sessionId), [sessionId]);

  if (session === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!session || session.deletedAt !== null) return <p>Entreno no encontrado.</p>;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{new Date(session.fecha).toLocaleDateString('es-ES')}</h1>
      <div className="space-y-3">
        {(ejercicios ?? []).map((le) => (
          <ExerciseDetail key={le.id} loggedExerciseId={le.id} exerciseId={le.exerciseId} />
        ))}
      </div>
      <Button
        variant="destructive"
        onClick={async () => {
          if (window.confirm('¿Borrar este entreno?')) {
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
