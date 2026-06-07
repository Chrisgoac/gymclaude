'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { ChevronUp, ChevronDown } from 'lucide-react';
import { listRoutines, reorderRoutines } from '@/lib/repositories/routines';

export function RoutineOrderManager() {
  const routines = useLiveQuery(() => listRoutines(), []);
  const lista = routines ?? [];

  async function mover(index: number, delta: number) {
    const destino = index + delta;
    if (destino < 0 || destino >= lista.length) return;
    const ids = lista.map((r) => r.id);
    [ids[index], ids[destino]] = [ids[destino], ids[index]];
    await reorderRoutines(ids);
  }

  return (
    <section className="space-y-3">
      <h2 className="label-mono text-[11px] text-muted-foreground">Orden de las rutinas</h2>
      <p className="label-mono text-[10px] text-muted-foreground">
        Define el ciclo para la sugerencia de &ldquo;siguiente rutina&rdquo;.
      </p>
      <ul className="brutal-box divide-y-2 divide-foreground">
        {lista.length === 0 && (
          <li className="label-mono px-3 py-3 text-xs text-muted-foreground">Sin rutinas todavía.</li>
        )}
        {lista.map((r, i) => (
          <li key={r.id} className="flex items-center justify-between gap-2 px-3 py-2.5">
            <span className="font-semibold">{r.nombre}</span>
            <span className="flex gap-1">
              <button
                aria-label={`Subir ${r.nombre}`}
                disabled={i === 0}
                className="border-2 border-foreground p-1 disabled:opacity-30"
                onClick={() => void mover(i, -1)}
              >
                <ChevronUp className="size-4" strokeWidth={3} />
              </button>
              <button
                aria-label={`Bajar ${r.nombre}`}
                disabled={i === lista.length - 1}
                className="border-2 border-foreground p-1 disabled:opacity-30"
                onClick={() => void mover(i, 1)}
              >
                <ChevronDown className="size-4" strokeWidth={3} />
              </button>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
