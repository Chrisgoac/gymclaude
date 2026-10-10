import { and, eq } from 'drizzle-orm';
import { z } from 'zod';
import { syncChanges, syncMutations } from '@/db/schema';
import type { PoolTx } from '@/db/pool';
import type { ApiPrincipal } from '@/lib/api/v1/auth';
import {
  SyncConflictSchema,
  type PushMutationDto,
  type PushRequest,
  type PushResponse,
  type SyncConflictDto,
  type SyncOperationDto,
  type SyncTableName,
} from '@/lib/api/v1/contracts/sync';
import { ApiError } from '@/lib/api/v1/errors';
import { decideVersionedWrite } from '@/lib/sync/server-merge';
import { SYNC_TABLE_ADAPTERS, type SyncRecordDto, type SyncTableAdapter } from './tables';

export type DbTransaction = PoolTx;

export interface PushOptions {
  /** Reloj del servidor (epoch ms). Inyectable en tests. */
  now?: number;
}

/** Margen máximo de reloj de cliente por delante del servidor. */
export const FUTURE_CLOCK_TOLERANCE_MS = 5 * 60_000;

// Fragmento de respuesta guardado en sync_mutations y repetido tal cual en los reintentos.
const StoredResponseSchema = z.object({ conflicts: z.array(SyncConflictSchema) }).strict();
type StoredResponse = z.infer<typeof StoredResponseSchema>;

/**
 * Aplica un push v1 dentro de la transacción `tx` que abre la ruta. Cada mutación lógica corre en
 * su propio `tx.transaction` (SAVEPOINT): sus operaciones, su diario y su fila de dedupe se
 * confirman juntas o no se confirma nada. Un error corta el request entero con un ApiError cuyo
 * `details.mutationId` identifica la mutación culpable. Requiere READ COMMITTED (el defecto): la
 * reclamación de dedupe relee la fila que confirmó una entrega concurrente.
 */
export async function pushMutations(
  tx: DbTransaction,
  principal: ApiPrincipal,
  request: PushRequest,
  options: PushOptions = {},
): Promise<PushResponse> {
  const now = options.now ?? Date.now();
  // Antes de tocar la BD: un reloj futuro invalida el request sin escribir nada.
  for (const mutation of request.mutations) assertNoFutureClock(mutation, now);

  const acceptedMutationIds: string[] = [];
  const conflicts: SyncConflictDto[] = [];
  for (const mutation of request.mutations) {
    let result: StoredResponse;
    try {
      result = await tx.transaction((mtx) => applyMutation(mtx, principal.userId, mutation, now));
    } catch (e) {
      throw withMutationId(e, mutation.mutationId);
    }
    acceptedMutationIds.push(mutation.mutationId);
    conflicts.push(...result.conflicts);
  }
  return { acceptedMutationIds, conflicts };
}

function assertNoFutureClock(mutation: PushMutationDto, now: number): void {
  const limit = now + FUTURE_CLOCK_TOLERANCE_MS;
  for (const { record } of mutation.operations) {
    if (record.updatedAt > limit || (record.deletedAt !== null && record.deletedAt > limit)) {
      throw new ApiError(400, 'FUTURE_TIMESTAMP', 'Record timestamp is too far in the future', false, {
        mutationId: [mutation.mutationId],
      });
    }
  }
}

function withMutationId(e: unknown, mutationId: string): unknown {
  if (!(e instanceof ApiError)) return e;
  return new ApiError(e.status, e.code, e.message, e.retryable, { ...e.details, mutationId: [mutationId] });
}

async function applyMutation(
  tx: DbTransaction,
  userId: string,
  mutation: PushMutationDto,
  now: number,
): Promise<StoredResponse> {
  const key = and(eq(syncMutations.userId, userId), eq(syncMutations.mutationId, mutation.mutationId));
  // Reclamar primero: con dos entregas concurrentes, la segunda espera aquí a que la primera
  // confirme y después repite su respuesta en vez de aplicar otra vez.
  const claimed = await tx
    .insert(syncMutations)
    .values({ userId, mutationId: mutation.mutationId, responseJson: { conflicts: [] }, processedAt: now })
    .onConflictDoNothing()
    .returning({ mutationId: syncMutations.mutationId });
  if (claimed.length === 0) {
    const [stored] = await tx.select({ responseJson: syncMutations.responseJson }).from(syncMutations).where(key);
    if (!stored) throw new ApiError(409, 'MUTATION_IN_FLIGHT', 'Mutation is being processed', true);
    return StoredResponseSchema.parse(stored.responseJson);
  }

  const conflicts: SyncConflictDto[] = [];
  for (const operation of mutation.operations) {
    const conflict = await applyOperation(tx, userId, mutation, operation, now);
    if (conflict) conflicts.push(conflict);
  }
  const response: StoredResponse = { conflicts };
  await tx.update(syncMutations).set({ responseJson: response }).where(key);
  return response;
}

async function applyOperation(
  tx: DbTransaction,
  userId: string,
  mutation: PushMutationDto,
  operation: SyncOperationDto,
  now: number,
): Promise<SyncConflictDto | null> {
  const table: SyncTableName = operation.table;
  const adapter = SYNC_TABLE_ADAPTERS[table] as SyncTableAdapter<SyncTableName> | undefined;
  if (!adapter) throw new ApiError(400, 'UNKNOWN_TABLE', 'Unknown table', false);
  const record = operation.record as SyncRecordDto<SyncTableName>;

  await adapter.assertReferences(tx, userId, record);

  const incoming = { updatedAt: record.updatedAt, deviceId: mutation.deviceId, mutationId: mutation.mutationId };
  const base = operation.baseServerVersion === null ? null : BigInt(operation.baseServerVersion);
  const conflict = (serverVersion: bigint | null, reason: string): SyncConflictDto => ({
    mutationId: mutation.mutationId,
    table,
    recordId: record.id,
    // Filas de escritores legacy posteriores a la migración pueden no tener versión aún.
    serverVersion: String(serverVersion ?? BigInt(0)),
    reason,
  });

  // Dos intentos: si otra transacción inserta el mismo id entre el SELECT y el INSERT, se
  // vuelve a bloquear la fila ya existente y se decide contra ella.
  for (let attempt = 0; attempt < 2; attempt++) {
    const existing = await adapter.lockOwnedRow(tx, userId, record.id);
    const decision = decideVersionedWrite(existing, incoming, base);
    if (!decision.apply) {
      return decision.conflict ? conflict(existing!.serverVersion, decision.conflict) : null;
    }

    const values = {
      ...adapter.toColumns(record),
      userId,
      lastDeviceId: mutation.deviceId,
      lastMutationId: mutation.mutationId,
      serverUpdatedAt: now,
    };
    if (!existing && !(await adapter.insertIfAbsent(tx, values))) continue;

    const [change] = await tx
      .insert(syncChanges)
      .values({ userId, tableName: table, recordId: record.id, changedAt: now })
      .returning({ sequence: syncChanges.sequence });
    const serverVersion = change.sequence;
    await adapter.update(tx, userId, record.id, existing ? { ...values, serverVersion } : { serverVersion });

    return decision.conflict ? conflict(serverVersion, decision.conflict) : null;
  }
  throw new ApiError(409, 'WRITE_RACE', 'Record changed concurrently, retry', true);
}
