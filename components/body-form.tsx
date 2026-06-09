'use client';

import { useState } from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { parseDecimal } from '@/lib/num';
import { addMetric } from '@/lib/repositories/body';
import {
  ORDEN_PREDEF,
  METRICAS_PREDEF,
  CLAVE_PERSONALIZADAS,
  addMetricaPersonalizada,
  type MetricaPersonalizada,
} from '@/lib/body-metrics';
import { useSetting } from '@/lib/use-setting';

function hoyISO(): string {
  const d = new Date();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

export function BodyForm() {
  const [personalizadas] = useSetting<MetricaPersonalizada[]>(CLAVE_PERSONALIZADAS, []);
  const [tipo, setTipo] = useState('peso');
  const [valor, setValor] = useState('');
  const [fecha, setFecha] = useState(hoyISO());
  const [error, setError] = useState('');
  const [gestion, setGestion] = useState(false);
  const [nombre, setNombre] = useState('');
  const [unidad, setUnidad] = useState('');
  const [gestionError, setGestionError] = useState('');

  async function registrar(e: React.FormEvent) {
    e.preventDefault();
    const num = parseDecimal(valor.replace(',', '.'));
    if (num <= 0) {
      setError('Introduce un valor mayor que 0');
      return;
    }
    const fechaMs = new Date(`${fecha}T00:00:00`).getTime();
    await addMetric(tipo, num, fechaMs);
    setValor('');
    setError('');
  }

  async function crearPersonalizada(e: React.FormEvent) {
    e.preventDefault();
    try {
      await addMetricaPersonalizada(nombre, unidad);
      setNombre('');
      setUnidad('');
      setGestionError('');
    } catch (err) {
      setGestionError(err instanceof Error ? err.message : 'No se pudo crear');
    }
  }

  return (
    <div className="space-y-4">
      <form onSubmit={registrar} className="brutal-box space-y-3 p-3">
        <div className="space-y-1">
          <Label htmlFor="metrica">Métrica</Label>
          <select
            id="metrica"
            className="w-full border-2 border-foreground bg-card p-2"
            value={tipo}
            onChange={(e) => setTipo(e.target.value)}
          >
            {ORDEN_PREDEF.map((k) => (
              <option key={k} value={k}>
                {METRICAS_PREDEF[k].label} ({METRICAS_PREDEF[k].unidad})
              </option>
            ))}
            {personalizadas.map((m) => (
              <option key={m.clave} value={m.clave}>
                {m.label} ({m.unidad})
              </option>
            ))}
          </select>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-1">
            <Label htmlFor="valor">Valor</Label>
            <Input
              id="valor"
              inputMode="decimal"
              value={valor}
              onChange={(e) => setValor(e.target.value)}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="fecha">Fecha</Label>
            <input
              id="fecha"
              type="date"
              className="h-9 w-full border-2 border-foreground bg-card px-2"
              value={fecha}
              onChange={(e) => setFecha(e.target.value)}
            />
          </div>
        </div>

        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button type="submit" className="w-full">
          Registrar
        </Button>
      </form>

      <button
        type="button"
        className="label-mono text-[11px] text-muted-foreground underline"
        onClick={() => setGestion((g) => !g)}
      >
        Gestionar métricas
      </button>

      {gestion && (
        <form onSubmit={crearPersonalizada} className="brutal-box space-y-3 p-3">
          <p className="label-mono text-[10px] text-muted-foreground">Nueva métrica personalizada</p>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <Label htmlFor="nombre">Nombre</Label>
              <Input id="nombre" value={nombre} onChange={(e) => setNombre(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="unidad">Unidad</Label>
              <Input id="unidad" value={unidad} onChange={(e) => setUnidad(e.target.value)} />
            </div>
          </div>
          {gestionError && <p className="text-sm text-destructive">{gestionError}</p>}
          <Button type="submit" variant="outline" className="w-full">
            Añadir métrica
          </Button>
        </form>
      )}
    </div>
  );
}
