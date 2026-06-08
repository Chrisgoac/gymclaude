'use client';

import { useEffect, useState } from 'react';
import { formatSegundos } from '@/lib/fecha';

/**
 * Cronómetro de descanso. Arranca cada vez que cambia `startKey` (>0).
 * Con `targetSeconds`: cuenta atrás y avisa (parpadeo + vibración) al llegar a 0.
 * Sin `targetSeconds`: cuenta hacia arriba como cronómetro libre.
 * Tocarlo lo detiene.
 */
export function RestTimer({ startKey, targetSeconds }: { startKey: number; targetSeconds?: number }) {
  const [activo, setActivo] = useState(false);
  const [transcurridos, setTranscurridos] = useState(0);

  // (Re)arranca cuando cambia startKey.
  useEffect(() => {
    if (startKey > 0) {
      setActivo(true);
      setTranscurridos(0);
    }
  }, [startKey]);

  // Tic de 1s mientras está activo.
  useEffect(() => {
    if (!activo) return;
    const id = setInterval(() => setTranscurridos((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [activo]);

  // Vibra al llegar a 0 (solo cuenta atrás). Recalcula "terminado" aquí dentro.
  useEffect(() => {
    const tieneObj = typeof targetSeconds === 'number' && targetSeconds > 0;
    if (tieneObj && targetSeconds - transcurridos <= 0
        && typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      navigator.vibrate?.(200);
    }
  }, [transcurridos, targetSeconds]);

  if (startKey === 0) return null;

  const tieneObjetivo = typeof targetSeconds === 'number' && targetSeconds > 0;
  const restante = tieneObjetivo ? targetSeconds - transcurridos : 0;
  const terminado = tieneObjetivo && restante <= 0;
  const display = tieneObjetivo ? formatSegundos(Math.max(0, restante)) : formatSegundos(transcurridos);

  return (
    <button
      type="button"
      onClick={() => setActivo(false)}
      className={`label-mono flex w-full items-center justify-center gap-2 border-2 border-foreground px-3 py-2 text-sm tabular-nums ${
        terminado ? 'animate-pulse bg-primary text-primary-foreground' : 'bg-card text-foreground'
      }`}
      aria-label="Descanso"
    >
      <span className="text-[10px] opacity-70">DESCANSO</span>
      <span className="font-[family-name:var(--font-display)] text-lg leading-none">{display}</span>
      {!activo && <span className="text-[10px] opacity-70">(parado)</span>}
    </button>
  );
}
