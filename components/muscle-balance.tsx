import type { VolumeByMuscle } from '@/lib/repositories/stats';
import { muscleGroupLabel } from '@/lib/labels';

export function MuscleBalance({ data }: { data: VolumeByMuscle[] }) {
  if (data.length === 0) return <p className="text-muted-foreground">Aún no hay volumen registrado.</p>;
  const max = Math.max(...data.map((d) => d.volumen), 1);
  return (
    <div className="space-y-2">
      {data.map((d) => (
        <div key={d.grupo}>
          <div className="label-mono mb-1 flex items-center justify-between text-[10px] text-muted-foreground">
            <span>{muscleGroupLabel[d.grupo]}</span>
            <span>{d.volumen} kg·rep</span>
          </div>
          <div className="h-3.5 border-2 border-foreground bg-card">
            <div className="h-full bg-primary" style={{ width: `${(d.volumen / max) * 100}%` }} />
          </div>
        </div>
      ))}
    </div>
  );
}
