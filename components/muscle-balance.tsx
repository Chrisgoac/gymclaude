import type { VolumeByMuscle } from '@/lib/repositories/stats';
import type { MuscleGroup } from '@/lib/db/types';
import { MUSCLE_GROUPS } from '@/lib/db/types';
import { muscleGroupLabel } from '@/lib/labels';

const DIA = 86400000;
const UMBRAL_DESCUIDADO_DIAS = 10;

export function MuscleBalance({
  data,
  lastTrained,
  ahora,
}: {
  data: VolumeByMuscle[];
  lastTrained?: Record<MuscleGroup, number | null>;
  /** Timestamp de referencia para calcular "hace N días". Debe venir de un useState lazy del padre. */
  ahora?: number;
}) {
  const volByGrupo = new Map(data.map((d) => [d.grupo, d.volumen]));
  // Grupos: con volumen en el periodo, o (si hay lastTrained) entrenados alguna vez → afloran los descuidados.
  const grupos = MUSCLE_GROUPS
    .filter((g) => volByGrupo.has(g) || (lastTrained ? lastTrained[g] != null : false))
    .sort((a, b) => (volByGrupo.get(b) ?? 0) - (volByGrupo.get(a) ?? 0));
  if (grupos.length === 0) return <p className="text-muted-foreground">Aún no hay volumen registrado.</p>;
  const max = Math.max(...grupos.map((g) => volByGrupo.get(g) ?? 0), 1);
  const ts = ahora ?? 0;
  return (
    <div className="space-y-2">
      {grupos.map((g) => {
        const vol = volByGrupo.get(g) ?? 0;
        const ult = lastTrained?.[g] ?? null;
        const dias = ult == null ? null : Math.floor((ts - ult) / DIA);
        const descuidado = lastTrained != null && (ult == null || (dias as number) > UMBRAL_DESCUIDADO_DIAS);
        return (
          <div key={g}>
            <div className="label-mono mb-1 flex items-center justify-between text-[10px] text-muted-foreground">
              <span className={descuidado ? 'text-destructive' : ''}>{muscleGroupLabel[g]}</span>
              <span className={descuidado ? 'text-destructive' : ''}>
                {Math.round(vol)} kg·rep
                {lastTrained != null && ` · ${ult == null ? 'nunca' : `hace ${dias}d`}`}
              </span>
            </div>
            <div className="h-3.5 border-2 border-foreground bg-card">
              <div className="h-full bg-primary" style={{ width: `${(vol / max) * 100}%` }} />
            </div>
          </div>
        );
      })}
    </div>
  );
}
