'use client';

import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { getSession, listSessionExercises, finishSession } from '@/lib/repositories/workouts';
import { LoggedExerciseCard } from '@/components/logged-exercise-card';
import { Button } from '@/components/ui/button';

export default function RegistroPage() {
  const params = useParams<{ sessionId: string }>();
  const sessionId = params.sessionId;
  const router = useRouter();

  const session = useLiveQuery(() => getSession(sessionId), [sessionId]);
  const ejercicios = useLiveQuery(() => listSessionExercises(sessionId), [sessionId]);

  if (session === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!session || session.deletedAt !== null) return <p>Entreno no encontrado.</p>;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Entreno</h1>
        <span className="text-sm text-muted-foreground">
          {new Date(session.fecha).toLocaleDateString('es-ES')}
        </span>
      </div>

      {(ejercicios ?? []).length === 0 && <p className="text-muted-foreground">Añade ejercicios para empezar.</p>}

      <div className="space-y-3">
        {(ejercicios ?? []).map((le) => (
          <LoggedExerciseCard key={le.id} loggedExercise={le} sessionId={sessionId} />
        ))}
      </div>

      <Link
        href={`/entrenar/${sessionId}/anadir`}
        className="block rounded-md border border-dashed p-3 text-center text-sm text-primary"
      >
        + Añadir ejercicio
      </Link>

      <Button
        className="w-full"
        onClick={async () => {
          await finishSession(sessionId, {});
          router.push('/');
        }}
      >
        Finalizar entreno
      </Button>
    </div>
  );
}
