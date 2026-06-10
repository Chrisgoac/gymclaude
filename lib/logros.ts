const DIA = 86400000;
const SEMANA = 7 * DIA;

export interface LogroMetricas {
  sesionesTotales: number;
  volumenTotal: number;
  prsTotales: number;
  mejorRacha: number;
  mesociclosCompletados: number;
}

export interface LogroDef {
  clave: string;
  titulo: string;
  descripcion: string;
  criterio: (m: LogroMetricas) => boolean;
}

export const LOGROS_DEF: LogroDef[] = [
  { clave: 'sesiones-10', titulo: 'Calentando', descripcion: '10 entrenos', criterio: (m) => m.sesionesTotales >= 10 },
  { clave: 'sesiones-50', titulo: 'Constante', descripcion: '50 entrenos', criterio: (m) => m.sesionesTotales >= 50 },
  { clave: 'sesiones-100', titulo: 'Centurión', descripcion: '100 entrenos', criterio: (m) => m.sesionesTotales >= 100 },
  { clave: 'sesiones-250', titulo: 'Veterano', descripcion: '250 entrenos', criterio: (m) => m.sesionesTotales >= 250 },
  { clave: 'volumen-100k', titulo: '100K', descripcion: '100.000 kg movidos', criterio: (m) => m.volumenTotal >= 100_000 },
  { clave: 'volumen-500k', titulo: 'Medio millón', descripcion: '500.000 kg movidos', criterio: (m) => m.volumenTotal >= 500_000 },
  { clave: 'volumen-1m', titulo: 'Una tonelada x1000', descripcion: '1.000.000 kg movidos', criterio: (m) => m.volumenTotal >= 1_000_000 },
  { clave: 'racha-4', titulo: 'Racha de 4', descripcion: '4 semanas seguidas cumpliendo el objetivo', criterio: (m) => m.mejorRacha >= 4 },
  { clave: 'racha-8', titulo: 'Racha de 8', descripcion: '8 semanas seguidas cumpliendo el objetivo', criterio: (m) => m.mejorRacha >= 8 },
  { clave: 'racha-12', titulo: 'Racha de 12', descripcion: '12 semanas seguidas cumpliendo el objetivo', criterio: (m) => m.mejorRacha >= 12 },
  { clave: 'mesociclo-1', titulo: 'Planificador', descripcion: 'Completa tu primer mesociclo', criterio: (m) => m.mesociclosCompletados >= 1 },
];

/** Claves de los hitos cuyo criterio se cumple con las métricas dadas. Puro. */
export function evaluarLogros(m: LogroMetricas): string[] {
  return LOGROS_DEF.filter((d) => d.criterio(m)).map((d) => d.clave);
}

/**
 * Racha (actual + mejor) de semanas consecutivas cumpliendo el objetivo de sesiones.
 * `semanas` = conteo de sesiones por semana ISO (con su inicioTs). `inicioSemanaActual` = lunes de la semana en curso.
 * La semana actual en curso por debajo del objetivo no rompe la racha (se cuenta desde la previa). Puro.
 */
export function calcularRacha(
  semanas: { inicioTs: number; sesiones: number }[],
  objetivo: number,
  inicioSemanaActual: number,
): { actual: number; mejor: number } {
  const wk = (ts: number) => Math.round(ts / SEMANA);
  const cumple = new Set<number>();
  for (const s of semanas) if (s.sesiones >= objetivo) cumple.add(wk(s.inicioTs));

  // mejor: racha consecutiva más larga
  const idxs = [...cumple].sort((a, b) => a - b);
  let mejor = 0;
  let run = 0;
  let prev: number | null = null;
  for (const i of idxs) {
    run = prev !== null && i === prev + 1 ? run + 1 : 1;
    if (run > mejor) mejor = run;
    prev = i;
  }

  // actual: hacia atrás desde la semana actual (o la previa si la actual aún no cumple)
  const actualWk = wk(inicioSemanaActual);
  let cursor = cumple.has(actualWk) ? actualWk : actualWk - 1;
  let actual = 0;
  while (cumple.has(cursor)) {
    actual++;
    cursor--;
  }

  return { actual, mejor };
}
