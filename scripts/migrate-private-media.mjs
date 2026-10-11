// Aplica drizzle/0002_private_media_keys.sql (aditiva: metadatos de medios + cola de borrados R2).
// Idempotente: se puede ejecutar dos veces. Todo va en UNA transacción bajo un advisory lock,
// así dos ejecuciones simultáneas no se pisan y un fallo no deja la BD a medias.
// Requiere 0001 aplicada (scripts/migrate-sync-foundation.mjs).
//
// Conexión: MIGRATION_DATABASE_URL si está definida; si no, DATABASE_URL_UNPOOLED / DATABASE_URL
// de .env.local (convención de los otros scripts/migrate-*.mjs).
// Uso: MIGRATION_DATABASE_URL=postgresql://... node scripts/migrate-private-media.mjs
import { readFileSync } from 'node:fs';
import pg from 'pg';

const EXPECTED_COLUMNS = ['mime', 'bytes', 'width', 'height'];

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

const sql = readFileSync(new URL('../drizzle/0002_private_media_keys.sql', import.meta.url), 'utf8');
const client = new pg.Client({ connectionString: url });
await client.connect();

try {
  await client.query('BEGIN');
  await client.query("SELECT pg_advisory_xact_lock(hashtext('gymlog:migrate-private-media'))");
  const foundation = await client.query("SELECT to_regclass('sync_changes') IS NOT NULL AS exists");
  if (!foundation.rows[0].exists) throw new Error('Falta 0001 (sync_changes): ejecuta antes migrate-sync-foundation.mjs');

  const counts = {};
  for (const t of ['exercise_photos', 'progress_photos']) {
    counts[t] = Number((await client.query(`SELECT count(*) FROM "${t}"`)).rows[0].count);
  }
  await client.query(sql);

  // Comprobación dentro de la transacción: columnas presentes y ninguna fila perdida/alterada.
  for (const t of ['exercise_photos', 'progress_photos']) {
    const { rows } = await client.query(
      'SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = $1',
      [t],
    );
    const names = new Set(rows.map((r) => r.column_name));
    for (const c of EXPECTED_COLUMNS) if (!names.has(c)) throw new Error(`${t}: falta la columna ${c}`);
    const after = Number((await client.query(`SELECT count(*) FROM "${t}"`)).rows[0].count);
    if (after !== counts[t]) throw new Error(`${t}: ${counts[t]} -> ${after} filas`);
    console.log(`  ${t}: ${after} filas, columnas de medios OK`);
  }
  const deletions = await client.query("SELECT to_regclass('media_deletions') IS NOT NULL AS exists");
  if (!deletions.rows[0].exists) throw new Error('media_deletions no existe');
  await client.query('COMMIT');
  console.log('0002_private_media_keys OK');
} catch (err) {
  await client.query('ROLLBACK').catch(() => {});
  throw err;
} finally {
  await client.end();
}
