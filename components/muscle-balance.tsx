import type { VolumeByMuscle } from '@/lib/repositories/stats';
import type { MuscleGroup } from '@/lib/db/types';
import { muscleGroupLabel } from '@/lib/labels';
import { filasEquilibrio } from '@/lib/insights';

// `lastTrained` y `ahora` van juntos: si pasas lastTrained, pasa también `ahora` (Date.now() vía useState lazy del padre).
export function MuscleBalance({
  data,
  lastTrained,
  ahora,
  objetivos,
  volumenSemana,
}: {
  data: VolumeByMuscle[];
  lastTrained?: Record<MuscleGroup, number | null>;
  /** Timestamp de referencia para calcular "hace N días". Debe venir de un useState lazy del padre. */
  ahora?: number;
  objetivos?: Partial<Record<MuscleGroup, number>>;
  volumenSemana?: Record<MuscleGroup, number>;
}) {
  const filas = filasEquilibrio({ data, lastTrained, ahora, objetivos, volumenSemana });
  if (filas.length === 0) return <p className="text-muted-foreground">Aún no hay volumen registrado.</p>;
  const max = Math.max(...filas.map((f) => f.volumen), 1);
  return (
    <div className="space-y-2">
      {filas.map(({ grupo: g, volumen: vol, diasSinEntrenar: dias, descuidado, metaPct }) => {
        const ult = lastTrained?.[g] ?? null;
        return (
          <div key={g}>
            <div className="label-mono mb-1 flex items-center justify-between text-[10px] text-muted-foreground">
              <span className={descuidado ? 'text-destructive' : ''}>{muscleGroupLabel[g]}</span>
              <span className={descuidado ? 'text-destructive' : ''}>
                {Math.round(vol)} kg·rep
                {metaPct != null && ` · ${metaPct}% meta sem.`}
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
