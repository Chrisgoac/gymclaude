'use client';

import { use } from 'react';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { softDeleteExercise } from '@/lib/repositories/exercises';
import { ExerciseForm } from '@/components/exercise-form';
import { Button } from '@/components/ui/button';

export default function EditarEjercicioPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const router = useRouter();
  const exercise = useLiveQuery(() => db.exercises.get(id), [id]);

  if (exercise === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  if (!exercise) return <p>Ejercicio no encontrado.</p>;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Editar ejercicio</h1>
      <ExerciseForm existing={exercise} onSaved={() => router.push('/ejercicios')} />
      <Button
        variant="destructive"
        onClick={async () => {
          await softDeleteExercise(id);
          router.push('/ejercicios');
        }}
      >
        Borrar
      </Button>
    </div>
  );
}
