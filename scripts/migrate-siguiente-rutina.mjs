// One-time: añade columnas para "última/siguiente rutina".
// Aditivo e idempotente. Uso: node scripts/migrate-siguiente-rutina.mjs
import { readFileSync } from 'node:fs';
import { neon } from '@neondatabase/serverless';

// Carga DATABASE_URL_UNPOOLED desde .env.local (sin dependencias extra).
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

await sql`ALTER TABLE workout_sessions ADD COLUMN IF NOT EXISTS routine_id text`;
await sql`ALTER TABLE routines ADD COLUMN IF NOT EXISTS orden integer`;

const cols = await sql`
  SELECT table_name, column_name
  FROM information_schema.columns
  WHERE (table_name = 'workout_sessions' AND column_name = 'routine_id')
     OR (table_name = 'routines' AND column_name = 'orden')
  ORDER BY table_name, column_name`;

console.log('Columnas presentes tras la migración:');
for (const c of cols) console.log(`  - ${c.table_name}.${c.column_name}`);
console.log('OK');
