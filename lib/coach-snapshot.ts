import type { MuscleGroup } from '@/lib/db/types';
import { MUSCLE_GROUPS } from '@/lib/db/types';
import { muscleGroupLabel } from '@/lib/labels';
import type { Estancado, WeeklySummary, PRSemana } from '@/lib/repositories/stats';

const DIA = 86400000;
const MAX_ESTANCADOS = 5;
const MAX_GRUPOS = 8;

export interface SnapshotInput {
  estancados: Estancado[];
  semana: WeeklySummary;
  objetivoSemanal: number;
  prs: PRSemana[];
  volumenSemanaPorGrupo: Record<MuscleGroup, number>;
  lastTrained: Record<MuscleGroup, number | null>;
  objetivosVolumen: Partial<Record<MuscleGroup, number>>;
  ahora: number;
}

export interface CoachSnapshot {
  estancados: { ejercicio: string; sesionesSinMejora: number }[];
  semana: {
    sesiones: number;
    objetivo: number;
    volumen: number;
    deltaPct: number | null;
    prs: { ejercicio: string; tipo: 'peso' | '1rm' }[];
  };
  grupos: { grupo: string; volumenSemana: number; diasSinEntrenar: number | null; objetivo: number | null }[];
}

/** Arma el contexto compacto del coach a partir de las señales ya consultadas. Pura. */
export function construirSnapshot(input: SnapshotInput): CoachSnapshot {
  const estancados = input.estancados
    .slice(0, MAX_ESTANCADOS)
    .map((e) => ({ ejercicio: e.nombre, sesionesSinMejora: e.sesionesSinMejora }));

  const semana = {
    sesiones: input.semana.sesiones,
    objetivo: input.objetivoSemanal,
    volumen: Math.round(input.semana.volumenSemana),
    deltaPct: input.semana.deltaPct,
    prs: input.prs.map((p) => ({ ejercicio: p.nombre, tipo: p.tipo })),
  };

  const grupos = MUSCLE_GROUPS
    .map((g) => {
      const vol = input.volumenSemanaPorGrupo[g] ?? 0;
      const ult = input.lastTrained[g] ?? null;
      const objetivo = input.objetivosVolumen[g] ?? null;
      const diasSinEntrenar = ult == null ? null : Math.floor((input.ahora - ult) / DIA);
      return { grupo: muscleGroupLabel[g], volumenSemana: Math.round(vol), diasSinEntrenar, objetivo, _vol: vol, _ult: ult };
    })
    .filter((g) => g._vol > 0 || g.objetivo != null || g._ult != null)
    .sort((a, b) => b._vol - a._vol)
    .slice(0, MAX_GRUPOS)
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    .map(({ _vol, _ult, ...rest }) => rest);

  return { estancados, semana, grupos };
}
