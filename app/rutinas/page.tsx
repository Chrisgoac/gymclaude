import Link from 'next/link';
import { RoutineList } from '@/components/routine-list';

export default function RutinasPage() {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Rutinas</h1>
        <Link href="/rutinas/nueva" className="rounded-md bg-primary px-3 py-1.5 text-sm text-primary-foreground">
          Nueva
        </Link>
      </div>
      <RoutineList />
    </div>
  );
}
