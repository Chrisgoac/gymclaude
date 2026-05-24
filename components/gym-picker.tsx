'use client';

import Link from 'next/link';
import { useLiveQuery } from 'dexie-react-hooks';
import { listGyms } from '@/lib/repositories/gyms';

export function GymPicker({ onPick }: { onPick: (gymId: string) => void }) {
  const gyms = useLiveQuery(() => listGyms(), []);
  if (gyms === undefined) return <p className="label-mono text-xs text-muted-foreground">Cargando…</p>;
  if (gyms.length === 0) {
    return (
      <div className="brutal-box p-4 text-center">
        <p className="label-mono mb-3 text-xs text-muted-foreground">Aún no tienes gimnasios</p>
        <Link
          href="/ajustes"
          className="label-mono inline-block border-2 border-foreground bg-primary px-3 py-2 text-[11px] text-primary-foreground brutal-shadow-sm"
        >
          Crea tu primer gimnasio
        </Link>
      </div>
    );
  }
  return (
    <div className="space-y-2">
      <p className="label-mono text-[11px] text-muted-foreground">¿En qué gimnasio entrenas hoy?</p>
      <div className="grid grid-cols-2 gap-2">
        {gyms.map((g) => (
          <button
            key={g.id}
            onClick={() => onPick(g.id)}
            className="brutal-box px-3 py-3 text-left font-[family-name:var(--font-display)] text-lg uppercase tracking-wide transition-transform active:translate-x-[2px] active:translate-y-[2px]"
          >
            {g.nombre}
          </button>
        ))}
      </div>
    </div>
  );
}
