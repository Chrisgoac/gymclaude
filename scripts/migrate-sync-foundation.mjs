// Aplica drizzle/0001_android_sync_foundation.sql (aditiva + backfill del diario de sync).
// Idempotente: se puede ejecutar dos veces. Todo va en UNA transacción bajo un advisory lock,
// así dos ejecuciones simultáneas no se pisan y un fallo no deja la BD a medias.
//
// Conexión: MIGRATION_DATABASE_URL si está definida; si no, DATABASE_URL_UNPOOLED / DATABASE_URL
// de .env.local (convención de los otros scripts/migrate-*.mjs).
// Uso: MIGRATION_DATABASE_URL=postgresql://... node scripts/migrate-sync-foundation.mjs
import { readFileSync } from 'node:fs';
import pg from 'pg';

const SYNCED_TABLES = [
  'exercises', 'routines', 'routine_exercises', 'workout_sessions', 'logged_exercises',
  'logged_sets', 'gyms', 'exercise_photos', 'user_settings', 'coach_messages',
  'body_metrics', 'progress_photos', 'mesocycles', 'achievements',
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

const sql = readFileSync(new URL('../drizzle/0001_android_sync_foundation.sql', import.meta.url), 'utf8');
const client = new pg.Client({ connectionString: url });
await client.connect();

try {
  await client.query('BEGIN');
  await client.query("SELECT pg_advisory_xact_lock(hashtext('gymlog:migrate-sync-foundation'))");
  const before = await client.query('SELECT to_regclass(\'sync_changes\') IS NOT NULL AS exists');
  const journalBefore = before.rows[0].exists
    ? Number((await client.query('SELECT count(*) FROM sync_changes')).rows[0].count)
    : 0;
  await client.query(sql);
  const journalAfter = Number((await client.query('SELECT count(*) FROM sync_changes')).rows[0].count);

  // Comprobación dentro de la transacción: ninguna fila puede quedar sin journal.
  for (const t of SYNCED_TABLES) {
    const { rows } = await client.query(
      `SELECT count(*) AS total, count(*) FILTER (WHERE server_version IS NULL) AS missing FROM "${t}"`,
    );
    if (Number(rows[0].missing) > 0) throw new Error(`${t}: ${rows[0].missing} filas sin server_version`);
    console.log(`  ${t}: ${rows[0].total} filas con server_version`);
  }
  await client.query('COMMIT');
  console.log(`sync_changes: ${journalBefore} -> ${journalAfter} (+${journalAfter - journalBefore}). OK`);
} catch (err) {
  await client.query('ROLLBACK').catch(() => {});
  throw err;
} finally {
  await client.end();
}
