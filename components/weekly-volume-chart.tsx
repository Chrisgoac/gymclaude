'use client';

import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { WeeklyVolumePoint } from '@/lib/repositories/stats';

export function WeeklyVolumeChart({ data }: { data: WeeklyVolumePoint[] }) {
  const barras = data.map((p) => ({
    semana: new Date(p.semanaInicioTs).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit' }),
    volumen: p.volumen,
  }));
  return (
    <div className="h-48 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={barras} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <XAxis dataKey="semana" fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <YAxis fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <Tooltip
            cursor={{ fill: 'currentColor', fillOpacity: 0.08 }}
            contentStyle={{ border: '2px solid currentColor', borderRadius: 0, fontFamily: 'var(--font-mono)', fontSize: 12 }}
          />
          <Bar dataKey="volumen" className="text-primary" fill="currentColor" stroke="currentColor" />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
