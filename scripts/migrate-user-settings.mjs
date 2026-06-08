// Crea la tabla user_settings si no existe (idempotente).
// Uso: node scripts/migrate-user-settings.mjs
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
  CREATE TABLE IF NOT EXISTS user_settings (
    id text NOT NULL,
    user_id text NOT NULL,
    valor text NOT NULL,
    updated_at bigint NOT NULL,
    deleted_at bigint,
    server_updated_at bigint NOT NULL,
    PRIMARY KEY (user_id, id)
  )
`;

console.log('Tabla user_settings creada (o ya existía). OK');
