'use client';

import { useState } from 'react';
import type { ProgressPhoto } from '@/lib/db/types';
import { ANGULOS, anguloLabel } from '@/lib/progress-photos';
import { deletePhoto } from '@/lib/repositories/progress-photos';

async function borrarEnR2(key: string): Promise<void> {
  try {
    await fetch('/api/progress-photos', {
      method: 'DELETE',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ key }),
    });
  } catch {
    // best-effort
  }
}

export function ProgressGallery({ fotos }: { fotos: ProgressPhoto[] }) {
  const [reveladas, setReveladas] = useState<Set<string>>(new Set());

  function toggle(id: string) {
    setReveladas((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function borrar(foto: ProgressPhoto) {
    const key = await deletePhoto(foto.id);
    if (key) void borrarEnR2(key);
  }

  return (
    <div className="space-y-4">
      {ANGULOS.map((ang) => {
        const delAngulo = fotos
          .filter((f) => f.angulo === ang)
          .sort((a, b) => b.fecha - a.fecha);
        if (delAngulo.length === 0) return null;
        return (
          <section key={ang} className="space-y-2">
            <h3 className="label-mono text-[10px] text-muted-foreground">{anguloLabel[ang]}</h3>
            <div className="grid grid-cols-3 gap-2">
              {delAngulo.map((f) => {
                const revelada = reveladas.has(f.id);
                return (
                  <div key={f.id} className="space-y-1">
                    <button
                      type="button"
                      onClick={() => toggle(f.id)}
                      className="block w-full"
                      aria-label={`${anguloLabel[ang]} ${revelada ? 'ocultar' : 'revelar'}`}
                    >
                      {/* eslint-disable-next-line @next/next/no-img-element */}
                      <img
                        src={f.url}
                        alt={`Foto ${anguloLabel[ang]}`}
                        className={`h-28 w-full border-2 border-foreground object-cover transition ${revelada ? '' : 'blur-md'}`}
                      />
                    </button>
                    <div className="flex items-center justify-between gap-1">
                      <span className="label-mono text-[9px] text-muted-foreground">
                        {new Date(f.fecha).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit', year: '2-digit' })}
                      </span>
                      <button
                        type="button"
                        className="grid size-6 place-items-center text-muted-foreground hover:text-destructive"
                        aria-label="Eliminar foto"
                        onClick={() => void borrar(f)}
                      >
                        ✕
                      </button>
                    </div>
                    {f.nota && <p className="text-[10px] text-muted-foreground">{f.nota}</p>}
                  </div>
                );
              })}
            </div>
          </section>
        );
      })}
    </div>
  );
}
