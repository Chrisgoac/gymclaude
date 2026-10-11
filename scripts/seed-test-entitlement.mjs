// Crea (o renueva) un derecho Pro de PRUEBA para un usuario en una BD LOCAL, para probar las rutas
// /api/v1/ai/* sin Google Play. No hay atajo equivalente en producción: el script se niega a correr
// salvo que MIGRATION_DATABASE_URL apunte a localhost/127.0.0.1/::1, y nunca lee .env.local.
//
// Uso:
//   MIGRATION_DATABASE_URL=postgresql://postgres:...@127.0.0.1:5432/gymlog_sync_test \
//     npm run ai:seed-test-entitlement -- --user user_abc [--days 30] [--state ACTIVE]
import { createHash } from 'node:crypto';
import pg from 'pg';

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]', '::1']);
const STATES = ['ACTIVE', 'GRACE', 'ON_HOLD', 'PAUSED', 'CANCELED', 'EXPIRED', 'REVOKED'];
const DAY = 86_400_000;

function fail(message) {
  console.error(message);
  process.exit(1);
}

function arg(name) {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? undefined : process.argv[i + 1];
}

const raw = process.env.MIGRATION_DATABASE_URL;
if (!raw) fail('Define MIGRATION_DATABASE_URL apuntando a una BD local (nunca producción).');
let target;
try {
  target = new URL(raw);
} catch {
  fail('MIGRATION_DATABASE_URL no es una URL válida.');
}
if (!LOCAL_HOSTS.has(target.hostname)) {
  fail(`Rechazado: ${target.hostname} no es localhost. Este seed solo existe para BD locales de prueba.`);
}

const userId = arg('user');
if (!userId || userId.startsWith('--')) fail('Falta --user <clerkUserId>.');
const state = arg('state') ?? 'ACTIVE';
if (!STATES.includes(state)) fail(`--state debe ser uno de ${STATES.join(', ')}.`);
const days = Number(arg('days') ?? 30);
if (!Number.isFinite(days)) fail('--days debe ser un número.');

const now = Date.now();
// Hash determinista por usuario: re-ejecutar renueva la misma fila en vez de acumular filas.
const tokenHash = createHash('sha256').update(`gymlog-test-entitlement:${userId}`).digest('hex');
const client = new pg.Client({ connectionString: raw });
await client.connect();
try {
  await client.query(
    `INSERT INTO subscription_entitlements
       (user_id, product_id, purchase_token_hash, state, expires_at, auto_renewing, last_verified_at, source, created_at, updated_at)
     VALUES ($1, 'gymlog_pro_test', $2, $3, $4, false, $5, 'test', $5, $5)
     ON CONFLICT (user_id, purchase_token_hash) DO UPDATE
       SET state = EXCLUDED.state, expires_at = EXCLUDED.expires_at,
           last_verified_at = EXCLUDED.last_verified_at, updated_at = EXCLUDED.updated_at`,
    [userId, tokenHash, state, now + days * DAY, now],
  );
  console.log(`Derecho de prueba ${state} para ${userId} hasta ${new Date(now + days * DAY).toISOString()} en ${target.hostname}${target.pathname}`);
} finally {
  await client.end();
}
