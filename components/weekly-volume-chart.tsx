'use client';

import { BarChart, Bar, XAxis, YAxis, Tooltip, LabelList, ResponsiveContainer } from 'recharts';
import type { WeeklyVolumePoint } from '@/lib/repositories/stats';
import { weeklyVolumeDeltas } from '@/lib/repositories/stats';

export function WeeklyVolumeChart({ data }: { data: WeeklyVolumePoint[] }) {
  const barras = weeklyVolumeDeltas(data).map((p) => ({
    semana: new Date(p.semanaInicioTs).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit' }),
    volumen: p.volumen,
    delta: p.deltaPct == null ? '' : `${p.deltaPct >= 0 ? '▲' : '▼'}${Math.abs(p.deltaPct)}%`,
  }));
  return (
    <div className="h-48 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={barras} margin={{ top: 16, right: 8, bottom: 0, left: -16 }}>
          <XAxis dataKey="semana" fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <YAxis domain={[0, (max: number) => Math.ceil(max * 1.1)]} fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <Tooltip
            cursor={{ fill: 'currentColor', fillOpacity: 0.08 }}
            contentStyle={{ border: '2px solid currentColor', borderRadius: 0, fontFamily: 'var(--font-mono)', fontSize: 12 }}
          />
          <Bar dataKey="volumen" className="text-primary" fill="currentColor" stroke="currentColor">
            <LabelList dataKey="delta" position="top" fontSize={10} fill="currentColor" />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
