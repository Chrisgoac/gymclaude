'use client';

import type { BodyMetric } from '@/lib/db/types';
import type { MetricaDef } from '@/lib/body-metrics';
import { resumenSerie } from '@/lib/body-stats';
import { deleteMetric } from '@/lib/repositories/body';
import { BodyMetricChart } from '@/components/body-metric-chart';

export function BodyMetricCard({
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  tipo,
  def,
  metrics,
}: {
  tipo: string;
  def: MetricaDef;
  metrics: BodyMetric[];
}) {
  const { actual, delta, puntos } = resumenSerie(metrics);
  const recientes = [...metrics].reverse();
  const signo = delta != null && delta > 0 ? '+' : '';

  return (
    <section className="brutal-box space-y-3 p-3" aria-label={def.label}>
      <header className="flex items-baseline justify-between">
        <h3 className="text-lg font-bold">{def.label}</h3>
        <div className="text-right">
          <span className="font-[family-name:var(--font-display)] text-2xl tabular-nums">
            {actual ?? '—'}
          </span>
          <span className="label-mono ml-1 text-[10px] text-muted-foreground">{def.unidad}</span>
          {delta != null && (
            <span
              className={`label-mono ml-2 text-[10px] ${delta < 0 ? 'text-primary' : 'text-muted-foreground'}`}
            >
              {signo}
              {delta.toFixed(1)} {def.unidad}
            </span>
          )}
        </div>
      </header>

      {puntos.length >= 2 && <BodyMetricChart puntos={puntos} />}

      <ul className="divide-y-2 divide-foreground border-t-2 border-foreground">
        {recientes.map((m) => (
          <li key={m.id} className="flex items-center justify-between gap-2 py-1.5">
            <span className="tabular-nums">
              {m.valor} {def.unidad}
            </span>
            <span className="flex items-center gap-3">
              <span className="label-mono text-[10px] text-muted-foreground">
                {new Date(m.fecha).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit', year: '2-digit' })}
              </span>
              <button
                type="button"
                className="grid size-7 place-items-center text-muted-foreground hover:text-destructive"
                aria-label={`Eliminar entrada ${def.label}`}
                onClick={() => void deleteMetric(m.id)}
              >
                ✕
              </button>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
