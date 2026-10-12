// Aplica drizzle/0004_billing_events.sql (aditiva: tabla billing_events de las notificaciones de
// Google Play + índice por hash de token en subscription_entitlements).
// Idempotente: se puede ejecutar dos veces. Todo va en UNA transacción bajo un advisory lock,
// así dos ejecuciones simultáneas no se pisan y un fallo no deja la BD a medias.
// Requiere 0003 aplicada (scripts/migrate-ai-entitlements.mjs).
//
// Conexión: MIGRATION_DATABASE_URL si está definida; si no, DATABASE_URL_UNPOOLED / DATABASE_URL
// de .env.local (convención de los otros scripts/migrate-*.mjs).
// Uso: MIGRATION_DATABASE_URL=postgresql://... node scripts/migrate-billing-events.mjs
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

const sql = readFileSync(new URL('../drizzle/0004_billing_events.sql', import.meta.url), 'utf8');
const client = new pg.Client({ connectionString: url });
await client.connect();

try {
  await client.query('BEGIN');
  await client.query("SELECT pg_advisory_xact_lock(hashtext('gymlog:migrate-billing-events'))");
  const entitlements = await client.query("SELECT to_regclass('subscription_entitlements') IS NOT NULL AS exists");
  if (!entitlements.rows[0].exists) {
    throw new Error('Falta 0003 (subscription_entitlements): ejecuta antes migrate-ai-entitlements.mjs');
  }

  await client.query(sql);

  // Comprobación dentro de la transacción: tabla, clave única e índice presentes.
  const table = await client.query("SELECT to_regclass('billing_events') IS NOT NULL AS exists");
  if (!table.rows[0].exists) throw new Error('billing_events no existe');
  const pk = await client.query(
    "SELECT indisunique FROM pg_index WHERE indexrelid = to_regclass('billing_events_pkey')",
  );
  if (!pk.rows[0]?.indisunique) throw new Error('falta la clave única de billing_events (message_id)');
  const idx = await client.query(
    "SELECT to_regclass('subscription_entitlements_purchase_token_hash_idx') IS NOT NULL AS exists",
  );
  if (!idx.rows[0].exists) throw new Error('falta subscription_entitlements_purchase_token_hash_idx');
  const n = Number((await client.query('SELECT count(*) FROM "billing_events"')).rows[0].count);
  console.log(`  billing_events: ${n} filas`);
  await client.query('COMMIT');
  console.log('0004_billing_events OK');
} catch (err) {
  await client.query('ROLLBACK').catch(() => {});
  throw err;
} finally {
  await client.end();
}
