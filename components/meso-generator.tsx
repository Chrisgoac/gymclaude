'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { recogerSnapshot } from '@/lib/coach-snapshot';
import { listExercises } from '@/lib/repositories/exercises';
import { guardarMesociclo } from '@/lib/save-mesocycle';
import type { PropuestaMesociclo } from '@/lib/meso-prompt';

const OBJETIVOS = ['hipertrofia', 'fuerza', 'general'];

export function MesoGenerator() {
  const router = useRouter();
  const [objetivo, setObjetivo] = useState('hipertrofia');
  const [diasPorSemana, setDias] = useState(4);
  const [semanas, setSemanas] = useState(6);
  const [minutosPorSesion, setMinutos] = useState(60);
  const [estado, setEstado] = useState<'form' | 'generando' | 'revision' | 'guardando'>('form');
  const [error, setError] = useState('');
  const [propuesta, setPropuesta] = useState<PropuestaMesociclo | null>(null);

  async function generar() {
    setError('');
    setEstado('generando');
    try {
      const snapshot = await recogerSnapshot();
      const catalogo = (await listExercises()).map((e) => ({
        nombre: e.nombre, grupo: e.grupoMuscular, equipamiento: e.equipamiento,
      }));
      const res = await fetch('/api/coach/mesociclo', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ params: { objetivo, diasPorSemana, semanas, minutosPorSesion }, snapshot, catalogo }),
      });
      if (!res.ok) throw new Error(String(res.status));
      const data = (await res.json()) as PropuestaMesociclo;
      setPropuesta(data);
      setEstado('revision');
    } catch {
      setError('No se pudo generar el mesociclo. Inténtalo de nuevo.');
      setEstado('form');
    }
  }

  async function guardar() {
    if (!propuesta) return;
    setEstado('guardando');
    try {
      const id = await guardarMesociclo(propuesta);
      router.push(`/mesociclo/${id}`);
    } catch {
      setError('No se pudo guardar.');
      setEstado('revision');
    }
  }

  if (estado === 'revision' || estado === 'guardando') {
    const p = propuesta!;
    return (
      <div className="space-y-4">
        <div className="brutal-box space-y-1 p-3">
          <h2 className="text-xl font-bold">{p.nombre}</h2>
          <p className="label-mono text-[10px] text-muted-foreground">
            {p.objetivo} · {p.semanas} semanas · {p.diasPorSemana} días/semana
          </p>
          {p.notas && <p className="text-sm text-muted-foreground">{p.notas}</p>}
        </div>

        <section className="brutal-box space-y-1 p-3">
          <h3 className="label-mono text-[10px] text-muted-foreground">Progresión</h3>
          <ul className="space-y-0.5 text-sm">
            {p.progresion.map((s) => (
              <li key={s.semana}>
                <span className="font-semibold">Sem {s.semana}{s.descarga ? ' (descarga)' : ''}:</span> {s.ajuste}
              </li>
            ))}
          </ul>
        </section>

        {[...p.dias].sort((a, b) => a.orden - b.orden).map((dia) => (
          <section key={dia.orden} className="brutal-box space-y-2 p-3">
            <h3 className="font-bold">{dia.nombre}</h3>
            <ul className="divide-y-2 divide-foreground">
              {dia.ejercicios.map((e, i) => (
                <li key={i} className="flex items-center justify-between gap-2 py-1.5 text-sm">
                  <span>
                    {e.nombre}
                    {e.nuevo && <span className="label-mono ml-2 text-[9px] text-primary">NUEVO</span>}
                  </span>
                  <span className="label-mono text-[10px] text-muted-foreground tabular-nums">
                    {e.seriesObjetivo}×{e.repsObjetivo} · {e.descansoSegundos}s
                  </span>
                </li>
              ))}
            </ul>
          </section>
        ))}

        {error && <p className="text-sm text-destructive">{error}</p>}
        <div className="flex gap-3">
          <Button onClick={guardar} disabled={estado === 'guardando'} className="flex-1">
            {estado === 'guardando' ? 'Guardando…' : 'Guardar mesociclo'}
          </Button>
          <Button variant="outline" onClick={() => setEstado('form')} disabled={estado === 'guardando'}>
            Descartar
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="brutal-box space-y-3 p-3">
      <div className="space-y-1">
        <Label htmlFor="objetivo">Objetivo</Label>
        <select id="objetivo" className="w-full border-2 border-foreground bg-card p-2" value={objetivo} onChange={(e) => setObjetivo(e.target.value)}>
          {OBJETIVOS.map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
      </div>
      <div className="grid grid-cols-3 gap-3">
        <div className="space-y-1">
          <Label htmlFor="dias">Días/semana</Label>
          <select id="dias" className="w-full border-2 border-foreground bg-card p-2" value={diasPorSemana} onChange={(e) => setDias(Number(e.target.value))}>
            {[2, 3, 4, 5, 6].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="semanas">Semanas</Label>
          <select id="semanas" className="w-full border-2 border-foreground bg-card p-2" value={semanas} onChange={(e) => setSemanas(Number(e.target.value))}>
            {[4, 5, 6, 7, 8].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
        <div className="space-y-1">
          <Label htmlFor="minutos">Min/sesión</Label>
          <select id="minutos" className="w-full border-2 border-foreground bg-card p-2" value={minutosPorSesion} onChange={(e) => setMinutos(Number(e.target.value))}>
            {[30, 45, 60, 75, 90].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      <Button onClick={generar} disabled={estado === 'generando'} className="w-full">
        {estado === 'generando' ? 'Generando…' : 'Generar mesociclo'}
      </Button>
    </div>
  );
}
