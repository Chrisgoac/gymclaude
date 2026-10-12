// Aplica drizzle/0005_account_deletion_jobs.sql (aditiva: tabla account_deletion_jobs del borrado de
// cuenta). Idempotente: se puede ejecutar dos veces. Todo va en UNA transacción bajo un advisory
// lock, así dos ejecuciones simultáneas no se pisan y un fallo no deja la BD a medias.
// Debe aplicarse ANTES de desplegar el código que la usa (el guard de acceso y lockUserSync la leen).
//
// Conexión: MIGRATION_DATABASE_URL si está definida; si no, DATABASE_URL_UNPOOLED / DATABASE_URL
// de .env.local (convención de los otros scripts/migrate-*.mjs).
// Uso: MIGRATION_DATABASE_URL=postgresql://... node scripts/migrate-account-deletion.mjs
import { readFileSync } from 'node:fs';
import pg from 'pg';

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

const sql = readFileSync(new URL('../drizzle/0005_account_deletion_jobs.sql', import.meta.url), 'utf8');
const client = new pg.Client({ connectionString: url });
await client.connect();

try {
  await client.query('BEGIN');
  await client.query("SELECT pg_advisory_xact_lock(hashtext('gymlog:migrate-account-deletion'))");
  const billing = await client.query("SELECT to_regclass('billing_events') IS NOT NULL AS exists");
  if (!billing.rows[0].exists) {
    throw new Error('Falta 0004 (billing_events): ejecuta antes migrate-billing-events.mjs');
  }

  await client.query(sql);

  // Comprobación dentro de la transacción: tabla, clave primaria e índice presentes.
  const table = await client.query("SELECT to_regclass('account_deletion_jobs') IS NOT NULL AS exists");
  if (!table.rows[0].exists) throw new Error('account_deletion_jobs no existe');
  const pk = await client.query(
    "SELECT indisunique FROM pg_index WHERE indexrelid = to_regclass('account_deletion_jobs_pkey')",
  );
  if (!pk.rows[0]?.indisunique) throw new Error('falta la clave primaria de account_deletion_jobs (user_id_hash)');
  const idx = await client.query("SELECT to_regclass('account_deletion_jobs_pending_idx') IS NOT NULL AS exists");
  if (!idx.rows[0].exists) throw new Error('falta account_deletion_jobs_pending_idx');
  const n = Number((await client.query('SELECT count(*) FROM "account_deletion_jobs"')).rows[0].count);
  console.log(`  account_deletion_jobs: ${n} filas`);
  await client.query('COMMIT');
  console.log('0005_account_deletion_jobs OK');
} catch (err) {
  await client.query('ROLLBACK').catch(() => {});
  throw err;
} finally {
  await client.end();
}
