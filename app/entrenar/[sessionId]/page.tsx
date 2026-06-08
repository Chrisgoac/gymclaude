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
      <div className="flex items-end justify-between border-b-2 border-foreground pb-3">
        <div>
          <p className="label-mono text-[11px] text-muted-foreground">En curso</p>
          <h1 className="text-4xl">Entreno</h1>
        </div>
        <span className="label-mono text-xs text-muted-foreground">
          {new Date(session.fecha).toLocaleDateString('es-ES')}
        </span>
      </div>

      {(ejercicios ?? []).length === 0 && (
        <p className="label-mono text-xs text-muted-foreground">Añade ejercicios para empezar.</p>
      )}

      <div className="space-y-4">
        {(ejercicios ?? []).map((le) => (
          <LoggedExerciseCard
            key={le.id}
            loggedExercise={le}
            sessionId={sessionId}
            gymId={session.gymId ?? undefined}
            routineId={session.routineId ?? undefined}
          />
        ))}
      </div>

      <Link
        href={`/entrenar/${sessionId}/anadir`}
        className="label-mono block border-2 border-dashed border-foreground bg-card/50 p-4 text-center text-xs text-foreground transition-colors hover:bg-card"
      >
        + Añadir ejercicio
      </Link>

      <Button
        size="lg"
        className="w-full font-[family-name:var(--font-display)] text-xl tracking-wide"
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
