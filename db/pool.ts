import { drizzle, type NodePgDatabase } from 'drizzle-orm/node-postgres';
import { Pool } from 'pg';
import * as schema from './schema';

// Cliente con transacciones interactivas (sync v1, rate-limit). db/client.ts (neon-http) no las
// soporta. El pool se crea al primer uso para no abrir conexiones al importar.
let db: NodePgDatabase<typeof schema> | undefined;

export function getPoolDb(): NodePgDatabase<typeof schema> {
  if (!db) {
    const connectionString = process.env.DATABASE_URL;
    if (!connectionString) throw new Error('DATABASE_URL is not set');
    // Pool pequeño: cada instancia serverless abre pocas conexiones y las suelta al quedar ociosa.
    db = drizzle(new Pool({ connectionString, max: 3, idleTimeoutMillis: 10_000 }), { schema });
  }
  return db;
}

export type PoolDb = NodePgDatabase<typeof schema>;
export type PoolTx = Parameters<Parameters<PoolDb['transaction']>[0]>[0];
