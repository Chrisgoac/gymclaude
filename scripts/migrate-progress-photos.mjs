// Crea la tabla progress_photos si no existe (idempotente).
// Uso: node scripts/migrate-progress-photos.mjs
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
  CREATE TABLE IF NOT EXISTS progress_photos (
    id text PRIMARY KEY,
    user_id text NOT NULL,
    updated_at bigint NOT NULL,
    deleted_at bigint,
    server_updated_at bigint NOT NULL,
    url text NOT NULL,
    key text NOT NULL,
    fecha bigint NOT NULL,
    angulo text NOT NULL,
    nota text
  )
`;

const cols = await sql`
  SELECT table_name, column_name
  FROM information_schema.columns
  WHERE table_name = 'progress_photos'
  ORDER BY table_name, column_name`;

console.log('Columnas presentes tras la migración:');
for (const c of cols) console.log(`  - ${c.table_name}.${c.column_name}`);
console.log('Tabla progress_photos creada (o ya existía). OK');
