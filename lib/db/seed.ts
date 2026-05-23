import { db } from './database';
import type { Exercise, MuscleGroup, Equipment, ExerciseType } from './types';

interface ExerciseDef {
  slug: string;
  nombre: string;
  grupoMuscular: MuscleGroup;
  equipamiento: Equipment;
  tipo: ExerciseType;
}

const DEFS: ExerciseDef[] = [
  // pecho
  { slug: 'press-banca',              nombre: 'Press de banca',                  grupoMuscular: 'pecho',       equipamiento: 'barra',        tipo: 'compuesto'  },
  { slug: 'press-inclinado-mancuerna',nombre: 'Press inclinado con mancuernas',  grupoMuscular: 'pecho',       equipamiento: 'mancuerna',    tipo: 'compuesto'  },
  { slug: 'aperturas-polea',          nombre: 'Aperturas en polea',              grupoMuscular: 'pecho',       equipamiento: 'polea',        tipo: 'aislamiento'},
  { slug: 'fondos',                   nombre: 'Fondos en paralelas',             grupoMuscular: 'pecho',       equipamiento: 'peso_corporal',tipo: 'compuesto'  },
  // espalda
  { slug: 'dominadas',                nombre: 'Dominadas',                       grupoMuscular: 'espalda',     equipamiento: 'peso_corporal',tipo: 'compuesto'  },
  { slug: 'jalon-al-pecho',           nombre: 'Jalón al pecho',                  grupoMuscular: 'espalda',     equipamiento: 'polea',        tipo: 'compuesto'  },
  { slug: 'remo-barra',               nombre: 'Remo con barra',                  grupoMuscular: 'espalda',     equipamiento: 'barra',        tipo: 'compuesto'  },
  { slug: 'remo-mancuerna',           nombre: 'Remo con mancuerna',              grupoMuscular: 'espalda',     equipamiento: 'mancuerna',    tipo: 'compuesto'  },
  { slug: 'peso-muerto',              nombre: 'Peso muerto',                     grupoMuscular: 'espalda',     equipamiento: 'barra',        tipo: 'compuesto'  },
  // hombros
  { slug: 'press-militar',            nombre: 'Press militar',                   grupoMuscular: 'hombros',     equipamiento: 'barra',        tipo: 'compuesto'  },
  { slug: 'elevaciones-laterales',    nombre: 'Elevaciones laterales',           grupoMuscular: 'hombros',     equipamiento: 'mancuerna',    tipo: 'aislamiento'},
  { slug: 'pajaros',                  nombre: 'Pájaros (deltoide posterior)',     grupoMuscular: 'hombros',     equipamiento: 'mancuerna',    tipo: 'aislamiento'},
  // biceps
  { slug: 'curl-barra',               nombre: 'Curl con barra',                  grupoMuscular: 'biceps',      equipamiento: 'barra',        tipo: 'aislamiento'},
  { slug: 'curl-martillo',            nombre: 'Curl martillo',                   grupoMuscular: 'biceps',      equipamiento: 'mancuerna',    tipo: 'aislamiento'},
  // triceps
  { slug: 'extension-polea',          nombre: 'Extensión de tríceps en polea',   grupoMuscular: 'triceps',     equipamiento: 'polea',        tipo: 'aislamiento'},
  { slug: 'press-frances',            nombre: 'Press francés',                   grupoMuscular: 'triceps',     equipamiento: 'barra',        tipo: 'aislamiento'},
  // cuadriceps
  { slug: 'sentadilla',               nombre: 'Sentadilla',                      grupoMuscular: 'cuadriceps',  equipamiento: 'barra',        tipo: 'compuesto'  },
  { slug: 'prensa',                   nombre: 'Prensa de piernas',               grupoMuscular: 'cuadriceps',  equipamiento: 'maquina',      tipo: 'compuesto'  },
  { slug: 'extension-cuadriceps',     nombre: 'Extensión de cuádriceps',         grupoMuscular: 'cuadriceps',  equipamiento: 'maquina',      tipo: 'aislamiento'},
  { slug: 'zancada',                  nombre: 'Zancadas',                        grupoMuscular: 'cuadriceps',  equipamiento: 'mancuerna',    tipo: 'compuesto'  },
  // femoral
  { slug: 'curl-femoral',             nombre: 'Curl femoral tumbado',            grupoMuscular: 'femoral',     equipamiento: 'maquina',      tipo: 'aislamiento'},
  { slug: 'peso-muerto-rumano',       nombre: 'Peso muerto rumano',              grupoMuscular: 'femoral',     equipamiento: 'barra',        tipo: 'compuesto'  },
  // gluteo
  { slug: 'hip-thrust',               nombre: 'Hip thrust',                      grupoMuscular: 'gluteo',      equipamiento: 'barra',        tipo: 'compuesto'  },
  // gemelo
  { slug: 'elevacion-talones',        nombre: 'Elevación de talones de pie',     grupoMuscular: 'gemelo',      equipamiento: 'maquina',      tipo: 'aislamiento'},
  // abdomen
  { slug: 'crunch',                   nombre: 'Crunch abdominal',                grupoMuscular: 'abdomen',     equipamiento: 'peso_corporal',tipo: 'aislamiento'},
  { slug: 'plancha',                  nombre: 'Plancha',                         grupoMuscular: 'abdomen',     equipamiento: 'peso_corporal',tipo: 'aislamiento'},
];

export const CATALOG_SEED: Exercise[] = DEFS.map((def) => ({
  id: `seed-${def.slug}`,
  userId: null,
  nombre: def.nombre,
  grupoMuscular: def.grupoMuscular,
  equipamiento: def.equipamiento,
  tipo: def.tipo,
  esPersonalizado: false,
  updatedAt: 0,
  deletedAt: null,
}));

export async function seedCatalogIfEmpty(): Promise<void> {
  const count = await db.exercises.count();
  if (count === 0) {
    await db.exercises.bulkPut(CATALOG_SEED);
  }
}
