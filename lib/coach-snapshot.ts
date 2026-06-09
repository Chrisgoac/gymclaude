import type { MuscleGroup } from '@/lib/db/types';
import { MUSCLE_GROUPS } from '@/lib/db/types';
import { muscleGroupLabel } from '@/lib/labels';
import type { Estancado, WeeklySummary, PRSemana } from '@/lib/repositories/stats';
import {
  listEstancados, getWeeklySummary, getPRsThisWeek,
  getVolumenSemanaByMuscle, getLastTrainedByMuscle,
} from '@/lib/repositories/stats';
import { getSetting } from '@/lib/repositories/user-settings';

const DIA = 86400000;
const MAX_ESTANCADOS = 5;
const MAX_GRUPOS = 8;

export interface SnapshotInput {
  /** Ya ordenados (peor primero) por el llamante; construirSnapshot solo recorta a los primeros 5. */
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
    .filter(
      (g) =>
        (input.volumenSemanaPorGrupo[g] ?? 0) > 0 ||
        input.objetivosVolumen[g] != null ||
        (input.lastTrained[g] ?? null) != null,
    )
    .sort((a, b) => (input.volumenSemanaPorGrupo[b] ?? 0) - (input.volumenSemanaPorGrupo[a] ?? 0))
    .slice(0, MAX_GRUPOS)
    .map((g) => {
      const ult = input.lastTrained[g] ?? null;
      return {
        grupo: muscleGroupLabel[g],
        volumenSemana: Math.round(input.volumenSemanaPorGrupo[g] ?? 0),
        diasSinEntrenar: ult == null ? null : Math.floor((input.ahora - ult) / DIA),
        objetivo: input.objetivosVolumen[g] ?? null,
      };
    });

  return { estancados, semana, grupos };
}

/** Consulta las señales de A/C (filtradas por gym) y arma el snapshot del coach. */
export async function recogerSnapshot(gymId?: string | null, now: number = Date.now()): Promise<CoachSnapshot> {
  const [estancados, semana, prs, volumenSemanaPorGrupo, lastTrained, objetivoSemanalRaw, objetivosVolumenRaw] = await Promise.all([
    listEstancados(gymId),
    getWeeklySummary(gymId, now),
    getPRsThisWeek(gymId, now),
    getVolumenSemanaByMuscle(gymId, now),
    getLastTrainedByMuscle(gymId),
    getSetting<number>('objetivoSemanal'),
    getSetting<Partial<Record<MuscleGroup, number>>>('objetivosVolumen'),
  ]);
  return construirSnapshot({
    estancados,
    semana,
    objetivoSemanal: objetivoSemanalRaw ?? 3,
    prs,
    volumenSemanaPorGrupo,
    lastTrained,
    objetivosVolumen: objetivosVolumenRaw ?? {},
    ahora: now,
  });
}
