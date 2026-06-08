'use client';

import { useEffect, useRef, useState } from 'react';
import { formatSegundos } from '@/lib/fecha';

/**
 * Cronómetro de descanso. El padre lo remonta (key={startKey}) en cada serie para reiniciarlo.
 * Con `targetSeconds`: cuenta atrás y avisa (parpadeo + vibración) una sola vez al llegar a 0.
 * Sin `targetSeconds`: cuenta hacia arriba como cronómetro libre.
 * Tocarlo lo detiene.
 */
export function RestTimer({ startKey, targetSeconds }: { startKey: number; targetSeconds?: number }) {
  const corriendo = startKey > 0;
  const tieneObjetivo = typeof targetSeconds === 'number' && targetSeconds > 0;
  const [transcurridos, setTranscurridos] = useState(0);
  const [parado, setParado] = useState(false);
  const haVibrado = useRef(false);

  const congelado = tieneObjetivo && transcurridos >= (targetSeconds as number);

  // Tic de 1s mientras corre, no está parado, ni congelado al alcanzar el objetivo.
  // setState solo dentro del callback del intervalo (permitido por la regla de hooks).
  useEffect(() => {
    if (!corriendo || parado || congelado) return;
    const id = setInterval(() => setTranscurridos((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [corriendo, parado, congelado]);

  // Vibra una sola vez al alcanzar el objetivo (efecto = llamada a sistema externo + ref, no setState).
  useEffect(() => {
    const tieneObj = typeof targetSeconds === 'number' && targetSeconds > 0;
    if (tieneObj && transcurridos >= (targetSeconds as number) && !haVibrado.current) {
      haVibrado.current = true;
      if (typeof navigator !== 'undefined' && 'vibrate' in navigator) {
        navigator.vibrate?.(200);
      }
    }
  }, [transcurridos, targetSeconds]);

  if (!corriendo) return null;

  const restante = tieneObjetivo ? (targetSeconds as number) - transcurridos : 0;
  const terminado = tieneObjetivo && restante <= 0;
  const display = tieneObjetivo ? formatSegundos(Math.max(0, restante)) : formatSegundos(transcurridos);

  return (
    <button
      type="button"
      onClick={() => setParado(true)}
      className={`label-mono flex w-full items-center justify-center gap-2 border-2 border-foreground px-3 py-2 text-sm tabular-nums ${
        terminado ? 'animate-pulse bg-primary text-primary-foreground' : 'bg-card text-foreground'
      }`}
      aria-label="Descanso"
    >
      <span className="text-[10px] opacity-70">DESCANSO</span>
      <span className="font-[family-name:var(--font-display)] text-lg leading-none">{display}</span>
      {parado && <span className="text-[10px] opacity-70">(parado)</span>}
    </button>
  );
}
