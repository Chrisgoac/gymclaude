import Link from 'next/link';
import { ExerciseList } from '@/components/exercise-list';

export default function EjerciciosPage() {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Ejercicios</h1>
        <Link href="/ejercicios/nuevo" className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground">
          Nuevo
        </Link>
      </div>
      <ExerciseList />
    </div>
  );
}
