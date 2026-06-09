import type { MuscleGroup } from '@/lib/db/types';
import { MUSCLE_GROUPS } from '@/lib/db/types';
import { muscleGroupLabel } from '@/lib/labels';
import type { Estancado, WeeklySummary, PRSemana } from '@/lib/repositories/stats';
import {
  listEstancados, getWeeklySummary, getPRsThisWeek,
  getVolumenSemanaByMuscle, getLastTrainedByMuscle,
} from '@/lib/repositories/stats';
import { getSetting } from '@/lib/repositories/user-settings';
import { listAllMetrics } from '@/lib/repositories/body';
import { resolverMetrica, CLAVE_PERSONALIZADAS, type MetricaPersonalizada } from '@/lib/body-metrics';

const DIA = 86400000;
const MAX_ESTANCADOS = 5;
const MAX_GRUPOS = 8;
const VENTANA_CUERPO = 28 * DIA;
const MAX_MEDIDAS = 6;

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
  /** Series corporales por tipo, entradas ya ordenadas asc por fecha. */
  cuerpo: { tipo: string; label: string; entradas: { valor: number; fecha: number }[] }[];
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
  cuerpo: {
    peso: { actual: number; delta4sem: number | null } | null;
    medidas: { metrica: string; actual: number; delta4sem: number | null }[];
  };
}

/** actual = última entrada; delta4sem vs la más antigua dentro de la ventana (null si <2 en ventana). */
function resumenCorporal(
  entradas: { valor: number; fecha: number }[],
  ahora: number,
): { actual: number; delta4sem: number | null } | null {
  if (entradas.length === 0) return null;
  const actual = entradas[entradas.length - 1].valor;
  const enVentana = entradas.filter((e) => e.fecha >= ahora - VENTANA_CUERPO);
  const delta4sem = enVentana.length >= 2 ? actual - enVentana[0].valor : null;
  return { actual, delta4sem };
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

  const pesoSerie = input.cuerpo.find((c) => c.tipo === 'peso');
  const peso = pesoSerie ? resumenCorporal(pesoSerie.entradas, input.ahora) : null;

  const medidas = input.cuerpo
    .filter((c) => c.tipo !== 'peso' && c.entradas.length > 0)
    .sort((a, b) => b.entradas[b.entradas.length - 1].fecha - a.entradas[a.entradas.length - 1].fecha)
    .slice(0, MAX_MEDIDAS)
    .map((c) => {
      const r = resumenCorporal(c.entradas, input.ahora)!;
      return { metrica: c.label, actual: r.actual, delta4sem: r.delta4sem };
    });

  const cuerpo = { peso, medidas };

  return { estancados, semana, grupos, cuerpo };
}

/** Consulta las señales de A/C (filtradas por gym) y arma el snapshot del coach. */
export async function recogerSnapshot(gymId?: string | null, now: number = Date.now()): Promise<CoachSnapshot> {
  const [estancados, semana, prs, volumenSemanaPorGrupo, lastTrained, objetivoSemanalRaw, objetivosVolumenRaw, bodyMetrics, personalizadasRaw] = await Promise.all([
    listEstancados(gymId),
    getWeeklySummary(gymId, now),
    getPRsThisWeek(gymId, now),
    getVolumenSemanaByMuscle(gymId, now),
    getLastTrainedByMuscle(gymId),
    getSetting<number>('objetivoSemanal'),
    getSetting<Partial<Record<MuscleGroup, number>>>('objetivosVolumen'),
    listAllMetrics(),
    getSetting<MetricaPersonalizada[]>(CLAVE_PERSONALIZADAS),
  ]);

  const personalizadas = personalizadasRaw ?? [];
  const porTipo = new Map<string, { valor: number; fecha: number }[]>();
  for (const m of bodyMetrics) {
    const arr = porTipo.get(m.tipo) ?? [];
    arr.push({ valor: m.valor, fecha: m.fecha });
    porTipo.set(m.tipo, arr);
  }
  const cuerpo = [...porTipo.entries()].map(([tipo, entradas]) => ({
    tipo,
    label: resolverMetrica(tipo, personalizadas).label,
    entradas,
  }));

  return construirSnapshot({
    estancados,
    semana,
    objetivoSemanal: objetivoSemanalRaw ?? 3,
    prs,
    volumenSemanaPorGrupo,
    lastTrained,
    objetivosVolumen: objetivosVolumenRaw ?? {},
    ahora: now,
    cuerpo,
  });
}
