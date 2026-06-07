'use client';

import { useState } from 'react';
import { useLiveQuery } from 'dexie-react-hooks';
import { listExercises } from '@/lib/repositories/exercises';
import {
  getVolumeByMuscle,
  getPeriodSummary,
  getWeeklyVolume,
  getCurrentStreakDays,
} from '@/lib/repositories/stats';
import { ExerciseProgress } from '@/components/exercise-progress';
import { WeeklyVolumeChart } from '@/components/weekly-volume-chart';
import { MuscleBalance } from '@/components/muscle-balance';
import { PeriodSelector } from '@/components/period-selector';
import { StatCard } from '@/components/stat-card';
import { GymFilter } from '@/components/gym-filter';
import { useGymFilter, filtroAGymId } from '@/lib/gym-filter';
import { periodoASinceTs, type Periodo } from '@/lib/period';

function formatoVolumen(kg: number): string {
  return kg >= 1000 ? `${(kg / 1000).toFixed(1)}t` : `${kg}`;
}

export default function ProgresoPage() {
  const ejercicios = useLiveQuery(() => listExercises(), []);
  const [filtro] = useGymFilter();
  const gymId = filtroAGymId(filtro);
  const [periodo, setPeriodo] = useState<Periodo>('4s');
  const sinceTs = periodoASinceTs(periodo);

  const racha = useLiveQuery(() => getCurrentStreakDays(), []);
  const resumen = useLiveQuery(() => getPeriodSummary(sinceTs, gymId), [sinceTs, gymId]);
  const semanal = useLiveQuery(() => getWeeklyVolume(sinceTs, gymId), [sinceTs, gymId]);
  const volumen = useLiveQuery(() => getVolumeByMuscle(sinceTs, gymId), [sinceTs, gymId]);
  const [seleccion, setSeleccion] = useState('');

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Progreso</h1>
      <GymFilter />
      <PeriodSelector value={periodo} onChange={setPeriodo} />

      <section className="grid grid-cols-3 gap-2">
        <StatCard valor={`${racha ?? 0}`} unidad="🔥 racha días" destacado />
        <StatCard valor={`${resumen?.sesiones ?? 0}`} unidad="sesiones" />
        <StatCard valor={formatoVolumen(resumen?.volumen ?? 0)} unidad="volumen" />
      </section>

      <section className="space-y-2">
        <h2 className="label-mono text-[10px] text-muted-foreground">Volumen semanal</h2>
        {(semanal ?? []).length === 0
          ? <p className="text-muted-foreground">Aún no hay sesiones en este periodo.</p>
          : <WeeklyVolumeChart data={semanal ?? []} />}
      </section>

      <section className="space-y-2">
        <label htmlFor="ejercicio" className="label-mono text-[10px] text-muted-foreground">
          Por ejercicio
        </label>
        <select
          id="ejercicio"
          className="w-full border-2 border-foreground bg-card p-2"
          value={seleccion}
          onChange={(e) => setSeleccion(e.target.value)}
        >
          <option value="">Elige un ejercicio…</option>
          {(ejercicios ?? []).map((e) => (
            <option key={e.id} value={e.id}>{e.nombre}</option>
          ))}
        </select>
        {seleccion && <ExerciseProgress exerciseId={seleccion} gymId={gymId} sinceTs={sinceTs} />}
      </section>

      <section className="space-y-2">
        <h2 className="label-mono text-[10px] text-muted-foreground">Balance muscular</h2>
        <MuscleBalance data={volumen ?? []} />
      </section>
    </div>
  );
}
