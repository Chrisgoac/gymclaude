import Link from 'next/link';
import { Sparkles } from 'lucide-react';
import { RoutineList } from '@/components/routine-list';

export default function RutinasPage() {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Rutinas</h1>
        <div className="flex items-center gap-2">
          <Link href="/rutinas/generar" className="flex items-center gap-1 rounded-md border-2 border-foreground px-3 py-1.5 text-sm">
            <Sparkles className="size-4 text-primary" strokeWidth={2} aria-hidden="true" />
            Generar con IA
          </Link>
          <Link href="/rutinas/nueva" className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground">
            Nueva
          </Link>
        </div>
      </div>
      <RoutineList />
    </div>
  );
}
