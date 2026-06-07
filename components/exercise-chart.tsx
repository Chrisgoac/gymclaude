'use client';

import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { ExerciseProgressPoint } from '@/lib/repositories/stats';

export type Metric = '1rm' | 'peso' | 'volumen';

const CAMPO: Record<Metric, keyof ExerciseProgressPoint> = {
  '1rm': 'mejor1RM',
  peso: 'maxPeso',
  volumen: 'volumen',
};

export function ExerciseChart({ data, metric }: { data: ExerciseProgressPoint[]; metric: Metric }) {
  const campo = CAMPO[metric];
  const puntos = data.map((p) => ({
    fecha: new Date(p.fecha).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit' }),
    valor: p[campo],
  }));
  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={puntos} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <XAxis dataKey="fecha" fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <YAxis fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <Tooltip
            cursor={{ stroke: 'currentColor', strokeWidth: 1 }}
            contentStyle={{ border: '2px solid currentColor', borderRadius: 0, fontFamily: 'var(--font-mono)', fontSize: 12 }}
          />
          <Line
            type="stepAfter"
            dataKey="valor"
            stroke="currentColor"
            className="text-primary"
            strokeWidth={3}
            dot={{ r: 3, strokeWidth: 0, fill: 'currentColor' }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
