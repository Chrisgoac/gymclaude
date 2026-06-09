'use client';

import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { PuntoSerie } from '@/lib/body-stats';

export function BodyMetricChart({ puntos }: { puntos: PuntoSerie[] }) {
  return (
    <div className="h-48 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={puntos} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <XAxis dataKey="fecha" fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <YAxis fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} domain={['auto', 'auto']} />
          <Tooltip
            cursor={{ stroke: 'currentColor', strokeWidth: 1 }}
            contentStyle={{ border: '2px solid currentColor', borderRadius: 0, fontFamily: 'var(--font-mono)', fontSize: 12 }}
          />
          <Line
            type="monotone"
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
