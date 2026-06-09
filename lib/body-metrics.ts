export interface MetricaDef {
  label: string;
  unidad: string;
}

export interface MetricaPersonalizada extends MetricaDef {
  clave: string;
}

/** Catálogo predefinido. peso en kg; el resto, medidas en cm. */
export const METRICAS_PREDEF: Record<string, MetricaDef> = {
  peso: { label: 'Peso', unidad: 'kg' },
  cintura: { label: 'Cintura', unidad: 'cm' },
  cadera: { label: 'Cadera', unidad: 'cm' },
  pecho: { label: 'Pecho', unidad: 'cm' },
  hombros: { label: 'Hombros', unidad: 'cm' },
  biceps: { label: 'Bíceps', unidad: 'cm' },
  antebrazo: { label: 'Antebrazo', unidad: 'cm' },
  muslo: { label: 'Muslo', unidad: 'cm' },
  pantorrilla: { label: 'Pantorrilla', unidad: 'cm' },
  cuello: { label: 'Cuello', unidad: 'cm' },
};

/** Orden de presentación en la UI (peso primero). */
export const ORDEN_PREDEF: readonly string[] = [
  'peso', 'cintura', 'cadera', 'pecho', 'hombros',
  'biceps', 'antebrazo', 'muslo', 'pantorrilla', 'cuello',
];

/** Slug estable a partir de un nombre libre: sin acentos, minúsculas, guiones. */
export function slugify(nombre: string): string {
  return nombre
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

/** Etiqueta + unidad de un tipo: predefinida → personalizada → fallback defensivo. */
export function resolverMetrica(
  tipo: string,
  personalizadas: MetricaPersonalizada[],
): MetricaDef {
  const predef = METRICAS_PREDEF[tipo];
  if (predef) return predef;
  const pers = personalizadas.find((m) => m.clave === tipo);
  if (pers) return { label: pers.label, unidad: pers.unidad };
  return { label: tipo, unidad: '' };
}
