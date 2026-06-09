'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { listAllMetrics } from '@/lib/repositories/body';
import { useSetting } from '@/lib/use-setting';
import {
  resolverMetrica,
  CLAVE_PERSONALIZADAS,
  ORDEN_PREDEF,
  type MetricaPersonalizada,
} from '@/lib/body-metrics';
import type { BodyMetric } from '@/lib/db/types';
import { BodyForm } from '@/components/body-form';
import { BodyMetricCard } from '@/components/body-metric-card';
import { ProgressPhotosSection } from '@/components/progress-photos-section';

export default function CuerpoPage() {
  const metrics = useLiveQuery(() => listAllMetrics(), []);
  const [personalizadas] = useSetting<MetricaPersonalizada[]>(CLAVE_PERSONALIZADAS, []);

  // Agrupa por tipo conservando orden cronológico asc dentro de cada grupo.
  const porTipo = new Map<string, BodyMetric[]>();
  for (const m of metrics ?? []) {
    const arr = porTipo.get(m.tipo) ?? [];
    arr.push(m);
    porTipo.set(m.tipo, arr);
  }

  // Orden de tarjetas: predefinidas primero (orden del catálogo), luego el resto por recencia.
  const tipos = [...porTipo.keys()].sort((a, b) => {
    const ia = ORDEN_PREDEF.indexOf(a);
    const ib = ORDEN_PREDEF.indexOf(b);
    if (ia !== -1 || ib !== -1) return (ia === -1 ? 999 : ia) - (ib === -1 ? 999 : ib);
    return a.localeCompare(b);
  });

  return (
    <div className="space-y-6">
      <div>
        <p className="label-mono text-[11px] text-muted-foreground">Seguimiento</p>
        <h1 className="text-5xl">Cuerpo</h1>
      </div>

      <BodyForm />

      {metrics === undefined ? (
        <p className="text-muted-foreground">Cargando…</p>
      ) : tipos.length === 0 ? (
        <p className="text-muted-foreground">
          Aún no has registrado ninguna medida. Empieza con tu peso arriba.
        </p>
      ) : (
        <div className="space-y-4">
          {tipos.map((tipo) => (
            <BodyMetricCard
              key={tipo}
              def={resolverMetrica(tipo, personalizadas)}
              metrics={porTipo.get(tipo)!}
            />
          ))}
        </div>
      )}

      <ProgressPhotosSection />
    </div>
  );
}
