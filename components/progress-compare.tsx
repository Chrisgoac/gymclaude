'use client';

import { useState } from 'react';
import type { ProgressPhoto, AnguloFoto } from '@/lib/db/types';
import { ANGULOS, anguloLabel } from '@/lib/progress-photos';

function fechaCorta(ms: number): string {
  return new Date(ms).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit', year: '2-digit' });
}

export function ProgressCompare({ fotos }: { fotos: ProgressPhoto[] }) {
  // Ángulos que califican: ≥2 fotos. Por ángulo, fotos en orden cronológico asc.
  const porAngulo = (a: AnguloFoto) => fotos.filter((f) => f.angulo === a).sort((x, y) => x.fecha - y.fecha);
  const angulosOK = ANGULOS.filter((a) => porAngulo(a).length >= 2);

  const [angulo, setAngulo] = useState<AnguloFoto | null>(angulosOK[0] ?? null);
  const activo = angulo && angulosOK.includes(angulo) ? angulo : (angulosOK[0] ?? null);

  const serie = activo ? porAngulo(activo) : [];
  const [idA, setIdA] = useState<string>(serie[0]?.id ?? '');
  const [idB, setIdB] = useState<string>(serie[serie.length - 1]?.id ?? '');

  if (angulosOK.length === 0 || !activo) return null;

  const fotoA = serie.find((f) => f.id === idA) ?? serie[0];
  const fotoB = serie.find((f) => f.id === idB) ?? serie[serie.length - 1];

  function cambiarAngulo(a: AnguloFoto) {
    setAngulo(a);
    const s = porAngulo(a);
    setIdA(s[0]?.id ?? '');
    setIdB(s[s.length - 1]?.id ?? '');
  }

  return (
    <section className="brutal-box space-y-3 p-3">
      <div className="flex items-center justify-between">
        <h3 className="label-mono text-[10px] text-muted-foreground">Comparar</h3>
        {angulosOK.length > 1 && (
          <select
            aria-label="Ángulo a comparar"
            className="border-2 border-foreground bg-card px-2 py-1 text-sm"
            value={activo}
            onChange={(e) => cambiarAngulo(e.target.value as AnguloFoto)}
          >
            {angulosOK.map((a) => (
              <option key={a} value={a}>
                {anguloLabel[a]}
              </option>
            ))}
          </select>
        )}
      </div>

      <div className="grid grid-cols-2 gap-2">
        {[
          { lado: 'A', sel: idA, set: setIdA, foto: fotoA },
          { lado: 'B', sel: idB, set: setIdB, foto: fotoB },
        ].map(({ lado, sel, set, foto: f }) => (
          <div key={lado} className="space-y-1">
            <select
              aria-label={`Foto ${lado}`}
              className="w-full border-2 border-foreground bg-card px-1 py-1 text-[11px]"
              value={sel}
              onChange={(e) => set(e.target.value)}
            >
              {serie.map((s) => (
                <option key={s.id} value={s.id}>
                  {fechaCorta(s.fecha)}
                </option>
              ))}
            </select>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={f.url}
              alt={`${anguloLabel[activo]} ${lado}`}
              className="h-44 w-full border-2 border-foreground object-cover"
            />
            <p className="label-mono text-center text-[9px] text-muted-foreground">{fechaCorta(f.fecha)}</p>
          </div>
        ))}
      </div>
    </section>
  );
}
