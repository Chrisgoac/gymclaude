'use client';

import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { ExerciseProgressPoint } from '@/lib/repositories/stats';

export function ExerciseChart({ data }: { data: ExerciseProgressPoint[] }) {
  const puntos = data.map((p) => ({
    fecha: new Date(p.fecha).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit' }),
    mejor1RM: p.mejor1RM,
  }));
  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={puntos} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <XAxis dataKey="fecha" fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <YAxis fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <Tooltip
            cursor={{ stroke: 'currentColor', strokeWidth: 1 }}
            contentStyle={{
              border: '2px solid currentColor',
              borderRadius: 0,
              fontFamily: 'var(--font-mono)',
              fontSize: 12,
            }}
          />
          <Line
            type="stepAfter"
            dataKey="mejor1RM"
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
