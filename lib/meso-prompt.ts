import type { CoachSnapshot } from '@/lib/coach-snapshot';

export interface MesoParams {
  objetivo: string;
  diasPorSemana: number;
  semanas: number;
  minutosPorSesion: number;
}

export const MESO_SCHEMA = {
  type: 'object',
  properties: {
    nombre: { type: 'string' },
    objetivo: { type: 'string' },
    semanas: { type: 'number' },
    diasPorSemana: { type: 'number' },
    notas: { type: 'string' },
    progresion: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          semana: { type: 'number' },
          descarga: { type: 'boolean' },
          ajuste: { type: 'string' },
        },
        required: ['semana', 'descarga', 'ajuste'],
        additionalProperties: false,
      },
    },
    dias: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          nombre: { type: 'string' },
          orden: { type: 'number' },
          ejercicios: {
            type: 'array',
            items: {
              type: 'object',
              properties: {
                nombre: { type: 'string' },
                grupoMuscular: { type: 'string' },
                equipamiento: { type: 'string' },
                tipo: { type: 'string' },
                seriesObjetivo: { type: 'number' },
                repsObjetivo: { type: 'number' },
                descansoSegundos: { type: 'number' },
                nuevo: { type: 'boolean' },
              },
              required: ['nombre', 'grupoMuscular', 'equipamiento', 'tipo', 'seriesObjetivo', 'repsObjetivo', 'descansoSegundos', 'nuevo'],
              additionalProperties: false,
            },
          },
        },
        required: ['nombre', 'orden', 'ejercicios'],
        additionalProperties: false,
      },
    },
  },
  required: ['nombre', 'objetivo', 'semanas', 'diasPorSemana', 'progresion', 'dias'],
  additionalProperties: false,
} as const;

/** Prompt para generar el mesociclo. Puro: parámetros + snapshot + catálogo. */
export function promptMesociclo(params: MesoParams, snapshot: CoachSnapshot, catalogo: { nombre: string; grupo: string; equipamiento: string }[]): string {
  const lista = catalogo.map((e) => `- ${e.nombre} (${e.grupo}, ${e.equipamiento})`).join('\n');
  return [
    'Eres un entrenador de fuerza experto. Diseña un MESOCICLO de entrenamiento.',
    `Objetivo: ${params.objetivo}. Días por semana: ${params.diasPorSemana}. Duración del mesociclo: ${params.semanas} semanas. Tiempo por sesión: ~${params.minutosPorSesion} min.`,
    '',
    'Reglas:',
    `- Crea exactamente ${params.diasPorSemana} días de entrenamiento (un split coherente).`,
    `- La progresión debe cubrir las ${params.semanas} semanas (incluye una semana de descarga si procede; márcala con descarga=true y un ajuste de menor volumen).`,
    '- Usa PREFERENTEMENTE ejercicios del catálogo proporcionado (por su nombre exacto). Si necesitas uno que no esté, propónlo con nuevo=true y su grupoMuscular/equipamiento/tipo.',
    '- grupoMuscular ∈ {pecho,espalda,hombros,biceps,triceps,cuadriceps,femoral,gluteo,gemelo,abdomen,antebrazo,otro}. equipamiento ∈ {barra,mancuerna,maquina,polea,peso_corporal,otro}. tipo ∈ {compuesto,aislamiento}.',
    '- Ajusta el volumen por grupo teniendo en cuenta los datos del atleta (más volumen donde flojea, atención a los estancados).',
    '- Los targets (series/reps/descanso) son los de la SEMANA 1 (base); la progresión semanal va en "progresion[].ajuste" como texto breve.',
    '',
    'Datos del atleta (JSON):',
    JSON.stringify(snapshot),
    '',
    'Catálogo de ejercicios disponibles:',
    lista || '(vacío)',
  ].join('\n');
}
