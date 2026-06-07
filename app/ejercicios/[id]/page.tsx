'use client';

import { use } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useLiveQuery } from 'dexie-react-hooks';
import { db } from '@/lib/db/database';
import { softDeleteExercise } from '@/lib/repositories/exercises';
import { muscleGroupLabel, equipmentLabel, exerciseTypeLabel } from '@/lib/labels';
import { ExerciseForm } from '@/components/exercise-form';
import { Button } from '@/components/ui/button';
import { ExercisePhotoPicker } from '@/components/exercise-photo-picker';
import { useDialogs } from '@/components/ui/dialog-provider';

export default function EditarEjercicioPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const router = useRouter();
  const { confirm } = useDialogs();
  const exercise = useLiveQuery(() => db.exercises.get(id), [id]);

  if (exercise === undefined) return <p className="text-muted-foreground">Cargando…</p>;
  // No encontrado o ya borrado (tombstone): no permitir editar ni "des-borrar".
  if (!exercise || exercise.deletedAt !== null) return <p>Ejercicio no encontrado.</p>;

  // El catálogo global es de solo lectura; para personalizarlo se crea uno propio.
  if (!exercise.esPersonalizado) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">{exercise.nombre}</h1>
        <ExercisePhotoPicker exerciseId={exercise.id} />
        <dl className="space-y-1 text-sm">
          <div>
            <span className="text-muted-foreground">Grupo: </span>
            {muscleGroupLabel[exercise.grupoMuscular]}
          </div>
          <div>
            <span className="text-muted-foreground">Equipamiento: </span>
            {equipmentLabel[exercise.equipamiento]}
          </div>
          <div>
            <span className="text-muted-foreground">Tipo: </span>
            {exerciseTypeLabel[exercise.tipo]}
          </div>
        </dl>
        {exercise.descripcion && (
          <section className="space-y-1">
            <h2 className="label-mono text-[11px] text-muted-foreground">Descripción</h2>
            <p className="whitespace-pre-line text-sm">{exercise.descripcion}</p>
          </section>
        )}
        {exercise.execution && (
          <section className="space-y-1">
            <h2 className="label-mono text-[11px] text-muted-foreground">Ejecución</h2>
            <p className="whitespace-pre-line text-sm">{exercise.execution}</p>
          </section>
        )}
        <p className="text-sm text-muted-foreground">
          Ejercicio del catálogo (solo lectura). Crea uno propio para personalizarlo.
        </p>
        <Link href="/ejercicios" className="text-primary underline">
          Volver
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Editar ejercicio</h1>
      <ExercisePhotoPicker exerciseId={exercise.id} />
      <ExerciseForm existing={exercise} onSaved={() => router.push('/ejercicios')} />
      <Button
        variant="destructive"
        onClick={async () => {
          if (!(await confirm({ titulo: `¿Borrar "${exercise.nombre}"?`, confirmar: 'Borrar', destructivo: true }))) return;
          await softDeleteExercise(id);
          router.push('/ejercicios');
        }}
      >
        Borrar
      </Button>
    </div>
  );
}
