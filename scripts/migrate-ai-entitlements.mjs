// Aplica drizzle/0003_ai_and_entitlements.sql (aditiva: tablas solo-servidor de Pro e IA).
// Idempotente: se puede ejecutar dos veces. Todo va en UNA transacción bajo un advisory lock,
// así dos ejecuciones simultáneas no se pisan y un fallo no deja la BD a medias.
// Requiere 0001 aplicada (scripts/migrate-sync-foundation.mjs).
//
// Conexión: MIGRATION_DATABASE_URL si está definida; si no, DATABASE_URL_UNPOOLED / DATABASE_URL
// de .env.local (convención de los otros scripts/migrate-*.mjs).
// Uso: MIGRATION_DATABASE_URL=postgresql://... node scripts/migrate-ai-entitlements.mjs
import { readFileSync } from 'node:fs';
import pg from 'pg';

const TABLES = ['subscription_entitlements', 'ai_reports', 'ai_responses', 'reported_ai_responses'];
// Columnas añadidas por revisiones posteriores de 0003 (ADD COLUMN IF NOT EXISTS): deben existir.
const REQUIRED_COLUMNS = {
  ai_responses: ['kind', 'content', 'attempt_id', 'lease_expires_at'],
};
const UNIQUE_INDEXES = [
  'ai_reports_user_kind_fingerprint_idx',
  'ai_responses_user_client_message_idx',
  'reported_ai_responses_user_response_idx',
];

function loadEnvLocal() {
  return Object.fromEntries(
    readFileSync(new URL('../.env.local', import.meta.url), 'utf8')
      .split('\n')
      .filter((l) => l.includes('='))
      .map((l) => {
        const i = l.indexOf('=');
        return [l.slice(0, i).trim(), l.slice(i + 1).trim().replace(/^["']|["']$/g, '')];
      }),
  );
}

function resolveUrl() {
  if (process.env.MIGRATION_DATABASE_URL) return { url: process.env.MIGRATION_DATABASE_URL, fromEnvLocal: false };
  const env = loadEnvLocal();
  const url = env.DATABASE_URL_UNPOOLED || env.DATABASE_URL;
  if (!url) throw new Error('No MIGRATION_DATABASE_URL ni DATABASE_URL(_UNPOOLED) en .env.local');
  return { url, fromEnvLocal: true };
}

const { url, fromEnvLocal } = resolveUrl();
const target = new URL(url);
console.log(`Destino: ${target.hostname}:${target.port || 5432}${target.pathname}`);
// .env.local es PRODUCCIÓN: exige confirmar el host explícitamente.
if (fromEnvLocal && process.env.CONFIRM_TARGET !== target.hostname) {
  console.error(`URL tomada de .env.local (producción). Repite con CONFIRM_TARGET=${target.hostname} para continuar.`);
  process.exit(1);
}

const sql = readFileSync(new URL('../drizzle/0003_ai_and_entitlements.sql', import.meta.url), 'utf8');
const client = new pg.Client({ connectionString: url });
await client.connect();

try {
  await client.query('BEGIN');
  await client.query("SELECT pg_advisory_xact_lock(hashtext('gymlog:migrate-ai-entitlements'))");
  const foundation = await client.query("SELECT to_regclass('sync_changes') IS NOT NULL AS exists");
  if (!foundation.rows[0].exists) throw new Error('Falta 0001 (sync_changes): ejecuta antes migrate-sync-foundation.mjs');

  await client.query(sql);

  // Comprobación dentro de la transacción: tablas e índices únicos presentes.
  for (const t of TABLES) {
    const r = await client.query('SELECT to_regclass($1) IS NOT NULL AS exists', [t]);
    if (!r.rows[0].exists) throw new Error(`${t} no existe`);
    const n = Number((await client.query(`SELECT count(*) FROM "${t}"`)).rows[0].count);
    console.log(`  ${t}: ${n} filas`);
  }
  for (const idx of UNIQUE_INDEXES) {
    const r = await client.query(
      'SELECT indisunique FROM pg_index WHERE indexrelid = to_regclass($1)',
      [idx],
    );
    if (!r.rows[0]?.indisunique) throw new Error(`falta el índice único ${idx}`);
  }
  for (const [table, columns] of Object.entries(REQUIRED_COLUMNS)) {
    const r = await client.query(
      "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = $1",
      [table],
    );
    const present = new Set(r.rows.map((row) => row.column_name));
    const missing = columns.filter((c) => !present.has(c));
    if (missing.length) throw new Error(`${table}: faltan columnas ${missing.join(', ')}`);
  }
  await client.query('COMMIT');
  console.log('0003_ai_and_entitlements OK');
} catch (err) {
  await client.query('ROLLBACK').catch(() => {});
  throw err;
} finally {
  await client.end();
}
