'use client';

import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import {
  listGyms, createGym, renameGym, softDeleteGym,
} from '@/lib/repositories/gyms';
import { countSessionsWithoutGym, assignGymToSessionsWithoutGym } from '@/lib/repositories/workouts';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { useDialogs } from '@/components/ui/dialog-provider';

export function GymManager() {
  const { confirm, prompt } = useDialogs();
  const gyms = useLiveQuery(() => listGyms(), []);
  const sinGym = useLiveQuery(() => countSessionsWithoutGym(), []);
  const [nombre, setNombre] = useState('');
  const [destino, setDestino] = useState('');

  async function añadir() {
    if (!nombre.trim()) return;
    await createGym(nombre);
    setNombre('');
  }

  return (
    <section className="space-y-3">
      <h2 className="label-mono text-[11px] text-muted-foreground">Gimnasios</h2>

      <div className="flex gap-2">
        <Input
          placeholder="Nombre del gimnasio"
          value={nombre}
          onChange={(e) => setNombre(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') void añadir(); }}
        />
        <Button onClick={añadir}>Añadir</Button>
      </div>

      <ul className="brutal-box divide-y-2 divide-foreground">
        {(gyms ?? []).length === 0 && (
          <li className="label-mono px-3 py-3 text-xs text-muted-foreground">Sin gimnasios todavía.</li>
        )}
        {(gyms ?? []).map((g) => (
          <li key={g.id} className="flex items-center justify-between gap-2 px-3 py-2.5">
            <span className="font-semibold">{g.nombre}</span>
            <span className="flex gap-2">
              <button
                className="label-mono text-[10px] text-muted-foreground hover:text-foreground"
                onClick={async () => {
                  const nuevo = await prompt({ titulo: 'Nuevo nombre', valorInicial: g.nombre, confirmar: 'Guardar' });
                  if (nuevo?.trim()) await renameGym(g.id, nuevo);
                }}
              >
                Renombrar
              </button>
              <button
                className="label-mono text-[10px] text-muted-foreground hover:text-destructive"
                onClick={async () => {
                  if (await confirm({ titulo: `¿Borrar "${g.nombre}"?`, mensaje: 'Sus entrenos se conservan.', confirmar: 'Borrar', destructivo: true })) {
                    await softDeleteGym(g.id);
                  }
                }}
              >
                Borrar
              </button>
            </span>
          </li>
        ))}
      </ul>

      {(sinGym ?? 0) > 0 && (gyms ?? []).length > 0 && (
        <div className="brutal-box space-y-2 p-3">
          <p className="label-mono text-[11px] text-muted-foreground">
            Tienes {sinGym} entreno{sinGym === 1 ? '' : 's'} sin gimnasio. Asígnalos a:
          </p>
          <div className="flex gap-2">
            <select
              className="h-11 flex-1 border-2 border-input bg-card px-2 text-base font-medium"
              value={destino}
              onChange={(e) => setDestino(e.target.value)}
            >
              <option value="">Elige…</option>
              {(gyms ?? []).map((g) => (
                <option key={g.id} value={g.id}>{g.nombre}</option>
              ))}
            </select>
            <Button
              disabled={!destino}
              onClick={async () => { if (destino) await assignGymToSessionsWithoutGym(destino); }}
            >
              Asignar
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}
