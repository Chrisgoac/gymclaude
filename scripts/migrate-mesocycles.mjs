// Crea la tabla mesocycles si no existe y añade mesocycle_id a routines (idempotente).
// Uso: node scripts/migrate-mesocycles.mjs
import { readFileSync } from 'node:fs';
import { neon } from '@neondatabase/serverless';

// Carga DATABASE_URL_UNPOOLED / DATABASE_URL desde .env.local (sin dependencias extra).
const env = Object.fromEntries(
  readFileSync(new URL('../.env.local', import.meta.url), 'utf8')
    .split('\n')
    .filter((l) => l.includes('='))
    .map((l) => {
      const i = l.indexOf('=');
      return [l.slice(0, i).trim(), l.slice(i + 1).trim().replace(/^["']|["']$/g, '')];
    }),
);

const url = env.DATABASE_URL_UNPOOLED || env.DATABASE_URL;
if (!url) throw new Error('No DATABASE_URL(_UNPOOLED) en .env.local');

const sql = neon(url);

await sql`
  CREATE TABLE IF NOT EXISTS mesocycles (
    id text PRIMARY KEY,
    user_id text NOT NULL,
    updated_at bigint NOT NULL,
    deleted_at bigint,
    server_updated_at bigint NOT NULL,
    nombre text NOT NULL,
    objetivo text NOT NULL,
    semanas integer NOT NULL,
    dias_por_semana integer NOT NULL,
    notas text,
    progresion jsonb NOT NULL,
    fecha_inicio bigint NOT NULL
  )
`;

await sql`
  ALTER TABLE routines ADD COLUMN IF NOT EXISTS mesocycle_id text
`;

const mesosCols = await sql`
  SELECT table_name, column_name
  FROM information_schema.columns
  WHERE table_name = 'mesocycles'
  ORDER BY table_name, column_name`;

console.log('Columnas presentes en mesocycles tras la migración:');
for (const c of mesosCols) console.log(`  - ${c.table_name}.${c.column_name}`);

const routinesCols = await sql`
  SELECT table_name, column_name
  FROM information_schema.columns
  WHERE table_name = 'routines' AND column_name = 'mesocycle_id'`;

if (routinesCols.length > 0) {
  console.log('  - routines.mesocycle_id: OK');
} else {
  console.log('  - routines.mesocycle_id: NO ENCONTRADA');
}

console.log('Migración completada. OK');
