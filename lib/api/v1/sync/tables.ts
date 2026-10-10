import { and, eq } from 'drizzle-orm';
import type { AnyPgColumn, PgTable } from 'drizzle-orm/pg-core';
import type { z } from 'zod';
import * as schema from '@/db/schema';
import type { PoolTx } from '@/db/pool';
import type { RecordSchemas, SyncTableName } from '@/lib/api/v1/contracts/sync';
import { ApiError } from '@/lib/api/v1/errors';
import type { StoredVersion } from '@/lib/sync/server-merge';

// Registro cerrado de las 14 tablas que el cliente puede escribir por la sync v1. El nombre de
// tabla del request solo sirve de clave de este objeto: nunca llega a SQL.

export type SyncRecordDto<T extends SyncTableName> = z.infer<(typeof RecordSchemas)[T]>;

type SyncPgTable = PgTable & {
  id: AnyPgColumn;
  userId: AnyPgColumn;
  updatedAt: AnyPgColumn;
  deletedAt: AnyPgColumn;
  serverVersion: AnyPgColumn;
  lastDeviceId: AnyPgColumn;
  lastMutationId: AnyPgColumn;
};

/** Columnas de dominio de un registro (sin userId ni metadatos de servidor). */
export type DomainColumns = Record<string, unknown> & { id: string; updatedAt: number; deletedAt: number | null };

/** Escritura completa: dominio + metadatos que fija el servidor. */
export type RowWrite = DomainColumns & {
  userId: string;
  lastDeviceId: string;
  lastMutationId: string;
  serverUpdatedAt: number;
  serverVersion?: bigint;
};

/** Fila actual (bloqueada) del usuario: versión para el merge + snapshot completo. */
export interface RowSnapshot extends StoredVersion {
  deletedAt: number | null;
  row: Record<string, unknown>;
}

export interface SyncTableAdapter<T extends SyncTableName> {
  readonly table: SyncPgTable;
  /** Bloquea (FOR UPDATE) la fila del usuario; null si no existe. Id de otro usuario → 409. */
  lockOwnedRow(tx: PoolTx, userId: string, id: string): Promise<RowSnapshot | null>;
  /** 400 si una referencia apunta a un id existente de otro usuario. Ids desconocidos se toleran. */
  assertReferences(tx: PoolTx, userId: string, record: SyncRecordDto<T>): Promise<void>;
  /** Mapeo explícito DTO → columnas: lo que no esté aquí no llega a la BD. */
  toColumns(record: SyncRecordDto<T>): DomainColumns;
  /** INSERT … ON CONFLICT DO NOTHING; false si otra transacción la creó antes. */
  insertIfAbsent(tx: PoolTx, values: RowWrite): Promise<boolean>;
  update(tx: PoolTx, userId: string, id: string, values: Partial<RowWrite>): Promise<Record<string, unknown>>;
}

type Reference = readonly [target: SyncPgTable, id: string | null | undefined];

interface AdapterSpec<T extends SyncTableName> {
  /** userSettings: PK (userId, id); el id es una clave de ajuste compartida entre usuarios. */
  idScopedToUser?: boolean;
  references?: (record: SyncRecordDto<T>) => Reference[];
  toColumns: (record: SyncRecordDto<T>) => DomainColumns;
}

const recordIdNotAvailable = () =>
  new ApiError(409, 'RECORD_ID_CONFLICT', 'Record id is not available', false);
const invalidReference = () =>
  new ApiError(400, 'INVALID_REFERENCE', 'Record references an unavailable record', false);

function defineAdapter<T extends SyncTableName>(table: SyncPgTable, spec: AdapterSpec<T>): SyncTableAdapter<T> {
  const t = table;
  const owned = (userId: string, id: string) => and(eq(t.id, id), eq(t.userId, userId));

  return {
    table,
    toColumns: spec.toColumns,

    async lockOwnedRow(tx, userId, id) {
      const rows = await tx
        .select()
        .from(t)
        .where(spec.idScopedToUser ? owned(userId, id) : eq(t.id, id))
        .for('update');
      const row = rows[0] as Record<string, unknown> | undefined;
      if (!row) return null;
      // Mismo mensaje y sin datos: no se revela de quién es ni qué contiene.
      if (row.userId !== userId) throw recordIdNotAvailable();
      return {
        updatedAt: row.updatedAt as number,
        deletedAt: row.deletedAt as number | null,
        deviceId: row.lastDeviceId as string | null,
        mutationId: row.lastMutationId as string | null,
        serverVersion: row.serverVersion as bigint | null,
        row,
      };
    },

    async assertReferences(tx, userId, record) {
      for (const [target, refId] of spec.references?.(record) ?? []) {
        if (!refId) continue;
        const found = await tx.select({ userId: target.userId }).from(target).where(eq(target.id, refId));
        if (found.some((r) => r.userId !== userId)) throw invalidReference();
      }
    },

    async insertIfAbsent(tx, values) {
      const inserted = await tx.insert(t).values(values as never).onConflictDoNothing().returning({ id: t.id });
      return inserted.length > 0;
    },

    async update(tx, userId, id, values) {
      const [row] = await tx.update(t).set(values as never).where(owned(userId, id)).returning();
      return row as Record<string, unknown>;
    },
  };
}

const common = (r: { id: string; updatedAt: number; deletedAt: number | null }) => ({
  id: r.id,
  updatedAt: r.updatedAt,
  deletedAt: r.deletedAt,
});
const n = <V>(v: V | null | undefined): V | null => v ?? null;

export const SYNC_TABLE_ADAPTERS = {
  exercises: defineAdapter<'exercises'>(schema.exercises, {
    toColumns: (r) => ({
      ...common(r),
      nombre: r.nombre, grupoMuscular: r.grupoMuscular, equipamiento: r.equipamiento, tipo: r.tipo,
      videoUrl: n(r.videoUrl), notas: n(r.notas), descripcion: n(r.descripcion), execution: n(r.execution),
      incrementoKg: n(r.incrementoKg), esPersonalizado: r.esPersonalizado,
    }),
  }),
  routines: defineAdapter<'routines'>(schema.routines, {
    references: (r) => [[schema.mesocycles, r.mesocycleId]],
    toColumns: (r) => ({
      ...common(r),
      nombre: r.nombre, descripcion: n(r.descripcion), orden: n(r.orden), archivada: r.archivada,
      mesocycleId: n(r.mesocycleId),
    }),
  }),
  routineExercises: defineAdapter<'routineExercises'>(schema.routineExercises, {
    references: (r) => [[schema.routines, r.routineId], [schema.exercises, r.exerciseId]],
    toColumns: (r) => ({
      ...common(r),
      routineId: n(r.routineId), exerciseId: r.exerciseId, orden: r.orden, seriesObjetivo: n(r.seriesObjetivo),
      repsObjetivo: n(r.repsObjetivo), repsObjetivoMin: n(r.repsObjetivoMin),
      descansoSegundos: n(r.descansoSegundos), notas: n(r.notas),
    }),
  }),
  workoutSessions: defineAdapter<'workoutSessions'>(schema.workoutSessions, {
    references: (r) => [[schema.gyms, r.gymId], [schema.routines, r.routineId]],
    toColumns: (r) => ({
      ...common(r),
      routineDayId: n(r.routineDayId), routineId: n(r.routineId), gymId: n(r.gymId), fecha: r.fecha,
      duracionSegundos: n(r.duracionSegundos), notas: n(r.notas),
    }),
  }),
  loggedExercises: defineAdapter<'loggedExercises'>(schema.loggedExercises, {
    references: (r) => [[schema.workoutSessions, r.sessionId], [schema.exercises, r.exerciseId]],
    toColumns: (r) => ({ ...common(r), sessionId: r.sessionId, exerciseId: r.exerciseId, orden: r.orden }),
  }),
  loggedSets: defineAdapter<'loggedSets'>(schema.loggedSets, {
    references: (r) => [[schema.loggedExercises, r.loggedExerciseId]],
    toColumns: (r) => ({
      ...common(r),
      loggedExerciseId: r.loggedExerciseId, orden: r.orden, peso: r.peso, reps: r.reps,
      esCalentamiento: n(r.esCalentamiento),
    }),
  }),
  gyms: defineAdapter<'gyms'>(schema.gyms, {
    toColumns: (r) => ({ ...common(r), nombre: r.nombre, orden: r.orden, archivada: r.archivada }),
  }),
  exercisePhotos: defineAdapter<'exercisePhotos'>(schema.exercisePhotos, {
    references: (r) => [[schema.exercises, r.exerciseId]],
    toColumns: (r) => ({ ...common(r), exerciseId: r.exerciseId, url: r.url, key: r.key }),
  }),
  userSettings: defineAdapter<'userSettings'>(schema.userSettings, {
    idScopedToUser: true,
    toColumns: (r) => ({ ...common(r), valor: r.valor }),
  }),
  coachMessages: defineAdapter<'coachMessages'>(schema.coachMessages, {
    toColumns: (r) => ({ ...common(r), rol: r.rol, contenido: r.contenido, createdAt: r.createdAt }),
  }),
  bodyMetrics: defineAdapter<'bodyMetrics'>(schema.bodyMetrics, {
    toColumns: (r) => ({ ...common(r), tipo: r.tipo, valor: r.valor, fecha: r.fecha }),
  }),
  progressPhotos: defineAdapter<'progressPhotos'>(schema.progressPhotos, {
    toColumns: (r) => ({
      ...common(r), url: r.url, key: r.key, fecha: r.fecha, angulo: r.angulo, nota: n(r.nota),
    }),
  }),
  mesocycles: defineAdapter<'mesocycles'>(schema.mesocycles, {
    toColumns: (r) => ({
      ...common(r),
      nombre: r.nombre, objetivo: r.objetivo, semanas: r.semanas, diasPorSemana: r.diasPorSemana,
      notas: n(r.notas), progresion: r.progresion, fechaInicio: r.fechaInicio,
    }),
  }),
  achievements: defineAdapter<'achievements'>(schema.achievements, {
    toColumns: (r) => ({ ...common(r), clave: r.clave, fechaDesbloqueo: r.fechaDesbloqueo }),
  }),
} satisfies { [K in SyncTableName]: SyncTableAdapter<K> };
