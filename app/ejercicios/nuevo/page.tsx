'use client';

import { useRouter } from 'next/navigation';
import { ExerciseForm } from '@/components/exercise-form';

export default function NuevoEjercicioPage() {
  const router = useRouter();
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Nuevo ejercicio</h1>
      <ExerciseForm onSaved={() => router.push('/ejercicios')} />
    </div>
  );
}
