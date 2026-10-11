import { pgTable, text, doublePrecision, integer, bigint, bigserial, boolean, jsonb, primaryKey, index, uniqueIndex, check } from 'drizzle-orm/pg-core';
import { sql } from 'drizzle-orm';

// Metadatos solo-servidor de la sync v1. Nunca llegan del cliente.
// serverVersion = sync_changes.sequence de la última escritura (nullable por compatibilidad con
// escritores legacy; la migración 0001 rellena todas las filas existentes).
function serverMeta() {
  return {
    serverVersion: bigint('server_version', { mode: 'bigint' }),
    lastDeviceId: text('last_device_id'),
    lastMutationId: text('last_mutation_id'),
  };
}

// Columnas comunes de sincronización en cada tabla.
const sync = {
  id: text('id').primaryKey(),
  userId: text('user_id').notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(), // reloj del cliente (LWW)
  deletedAt: bigint('deleted_at', { mode: 'number' }), // tombstone (nullable)
  serverUpdatedAt: bigint('server_updated_at', { mode: 'number' }).notNull(), // reloj del servidor (cursor de pull)
  ...serverMeta(),
};

export const exercises = pgTable('exercises', {
  ...sync,
  nombre: text('nombre').notNull(),
  grupoMuscular: text('grupo_muscular').notNull(),
  equipamiento: text('equipamiento').notNull(),
  tipo: text('tipo').notNull(),
  videoUrl: text('video_url'),
  notas: text('notas'),
  descripcion: text('descripcion'),
  execution: text('execution'),
  incrementoKg: doublePrecision('incremento_kg'),
  esPersonalizado: boolean('es_personalizado').notNull(),
});

export const routines = pgTable('routines', {
  ...sync,
  nombre: text('nombre').notNull(),
  descripcion: text('descripcion'),
  orden: integer('orden'),
  archivada: boolean('archivada').notNull(),
  mesocycleId: text('mesocycle_id'),
});

export const routineExercises = pgTable('routine_exercises', {
  ...sync,
  routineId: text('routine_id'),
  exerciseId: text('exercise_id').notNull(),
  orden: integer('orden').notNull(),
  seriesObjetivo: integer('series_objetivo'),
  repsObjetivo: integer('reps_objetivo'),
  repsObjetivoMin: integer('reps_objetivo_min'),
  descansoSegundos: integer('descanso_segundos'),
  notas: text('notas'),
});

export const workoutSessions = pgTable('workout_sessions', {
  ...sync,
  routineDayId: text('routine_day_id'),
  routineId: text('routine_id'),
  gymId: text('gym_id'),
  fecha: bigint('fecha', { mode: 'number' }).notNull(),
  duracionSegundos: integer('duracion_segundos'),
  notas: text('notas'),
});

export const loggedExercises = pgTable('logged_exercises', {
  ...sync,
  sessionId: text('session_id').notNull(),
  exerciseId: text('exercise_id').notNull(),
  orden: integer('orden').notNull(),
});

export const loggedSets = pgTable('logged_sets', {
  ...sync,
  loggedExerciseId: text('logged_exercise_id').notNull(),
  orden: integer('orden').notNull(),
  peso: doublePrecision('peso').notNull(),
  reps: integer('reps').notNull(),
  esCalentamiento: boolean('es_calentamiento'),
});

export const gyms = pgTable('gyms', {
  ...sync,
  nombre: text('nombre').notNull(),
  orden: integer('orden').notNull(),
  archivada: boolean('archivada').notNull(),
});

export const exercisePhotos = pgTable('exercise_photos', {
  ...sync,
  exerciseId: text('exercise_id').notNull(),
  url: text('url').notNull(),
  key: text('key').notNull(), // clave del objeto R2 (objectKey)
  // Metadatos de medios privados v1 (0002). Nulos en filas legacy de la PWA.
  mime: text('mime'),
  bytes: integer('bytes'),
  width: integer('width'),
  height: integer('height'),
});

export const userSettings = pgTable('user_settings', {
  id: text('id').notNull(),
  userId: text('user_id').notNull(),
  valor: text('valor').notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(),
  deletedAt: bigint('deleted_at', { mode: 'number' }),
  serverUpdatedAt: bigint('server_updated_at', { mode: 'number' }).notNull(),
  ...serverMeta(),
}, (t) => ({
  pk: primaryKey({ columns: [t.userId, t.id] }),
}));

export const coachMessages = pgTable('coach_messages', {
  ...sync,
  rol: text('rol').notNull(),
  contenido: text('contenido').notNull(),
  createdAt: bigint('created_at', { mode: 'number' }).notNull(),
});

export const bodyMetrics = pgTable('body_metrics', {
  ...sync,
  tipo: text('tipo').notNull(),
  valor: doublePrecision('valor').notNull(),
  fecha: bigint('fecha', { mode: 'number' }).notNull(),
});

export const progressPhotos = pgTable('progress_photos', {
  ...sync,
  url: text('url').notNull(),
  key: text('key').notNull(),
  fecha: bigint('fecha', { mode: 'number' }).notNull(),
  angulo: text('angulo').notNull(),
  nota: text('nota'),
  // Metadatos de medios privados v1 (0002). Nulos en filas legacy de la PWA.
  mime: text('mime'),
  bytes: integer('bytes'),
  width: integer('width'),
  height: integer('height'),
});

export const mesocycles = pgTable('mesocycles', {
  ...sync,
  nombre: text('nombre').notNull(),
  objetivo: text('objetivo').notNull(),
  semanas: integer('semanas').notNull(),
  diasPorSemana: integer('dias_por_semana').notNull(),
  notas: text('notas'),
  progresion: jsonb('progresion').notNull(),
  fechaInicio: bigint('fecha_inicio', { mode: 'number' }).notNull(),
});

export const achievements = pgTable('achievements', {
  ...sync,
  clave: text('clave').notNull(),
  fechaDesbloqueo: bigint('fecha_desbloqueo', { mode: 'number' }).notNull(),
});

// ── Infraestructura de la sync v1 (solo servidor, no se sincroniza) ──

// Dedupe de mutaciones: la respuesta guardada se repite tal cual en reintentos.
export const syncMutations = pgTable('sync_mutations', {
  userId: text('user_id').notNull(),
  mutationId: text('mutation_id').notNull(),
  responseJson: jsonb('response_json').notNull(),
  processedAt: bigint('processed_at', { mode: 'number' }).notNull(), // epoch ms
}, (t) => [primaryKey({ columns: [t.userId, t.mutationId] })]);

// Diario global de cambios: `sequence` es la única fuente de orden del pull.
export const syncChanges = pgTable('sync_changes', {
  sequence: bigserial('sequence', { mode: 'bigint' }).primaryKey(),
  userId: text('user_id').notNull(),
  tableName: text('table_name').notNull(),
  recordId: text('record_id').notNull(),
  changedAt: bigint('changed_at', { mode: 'number' }).notNull(), // epoch ms
}, (t) => [index('sync_changes_user_sequence_idx').on(t.userId, t.sequence)]);

// Contadores de rate-limit compartidos entre instancias (ventana fija).
export const apiRateBuckets = pgTable('api_rate_buckets', {
  scope: text('scope').notNull(),
  subjectHash: text('subject_hash').notNull(),
  windowStart: bigint('window_start', { mode: 'number' }).notNull(), // epoch ms
  count: integer('count').notNull(),
}, (t) => [primaryKey({ columns: [t.scope, t.subjectHash, t.windowStart] })]);

// Borrados diferidos de objetos R2 tras el tombstone de una foto (0002). Solo servidor: no se
// sincroniza. `due_at` es a la vez la fecha de vencimiento y el lease del proceso que la reclama.
export const mediaDeletions = pgTable('media_deletions', {
  objectKey: text('object_key').primaryKey(),
  userId: text('user_id').notNull(),
  dueAt: bigint('due_at', { mode: 'number' }).notNull(), // epoch ms
  attempts: integer('attempts').notNull().default(0),
  lastError: text('last_error'),
  createdAt: bigint('created_at', { mode: 'number' }).notNull(), // epoch ms
}, (t) => [index('media_deletions_due_at_idx').on(t.dueAt)]);

// ── Pro y artefactos de IA (0003). Solo servidor: ninguno se sincroniza ni lo escribe un cliente. ──

// Derecho Pro verificado por el servidor (la fase 6 lo rellena desde Google Play; `source='test'`
// solo lo crea scripts/seed-test-entitlement.mjs en BD locales). Nunca se guarda el purchase token
// en claro: solo su SHA-256. Concede Pro si `state` ∈ {ACTIVE, GRACE} y `expires_at` > ahora.
export const subscriptionEntitlements = pgTable('subscription_entitlements', {
  userId: text('user_id').notNull(),
  productId: text('product_id').notNull(),
  purchaseTokenHash: text('purchase_token_hash').notNull(),
  state: text('state').notNull(),
  expiresAt: bigint('expires_at', { mode: 'number' }).notNull(), // epoch ms
  autoRenewing: boolean('auto_renewing').notNull(),
  lastVerifiedAt: bigint('last_verified_at', { mode: 'number' }).notNull(), // epoch ms
  source: text('source').notNull(),
  createdAt: bigint('created_at', { mode: 'number' }).notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(),
}, (t) => [
  primaryKey({ columns: [t.userId, t.purchaseTokenHash] }),
  // RTDN finds the owner of a purchase by its token hash alone (the PK leads with user_id).
  index('subscription_entitlements_purchase_token_hash_idx').on(t.purchaseTokenHash),
  check('subscription_entitlements_state_check',
    sql`${t.state} IN ('ACTIVE','GRACE','ON_HOLD','PAUSED','CANCELED','EXPIRED','REVOKED')`),
  check('subscription_entitlements_source_check', sql`${t.source} IN ('play','test')`),
]);

// Google Play Real-Time Developer Notifications recibidas (Pub/Sub push). `message_id` (el messageId
// de Pub/Sub) hace idempotente la reentrega: un mensaje ya procesado se confirma sin reprocesar.
// Solo el hash del token, nunca el token. `processed_at` NULL = recibido pero pendiente (fallo
// transitorio de Google: Pub/Sub reintenta).
export const billingEvents = pgTable('billing_events', {
  messageId: text('message_id').primaryKey(),
  eventTime: bigint('event_time', { mode: 'number' }).notNull(), // epoch ms (eventTimeMillis de Google)
  notificationKind: text('notification_kind').notNull(),
  notificationType: integer('notification_type'),
  purchaseTokenHash: text('purchase_token_hash'),
  outcome: text('outcome'),
  receivedAt: bigint('received_at', { mode: 'number' }).notNull(),
  processedAt: bigint('processed_at', { mode: 'number' }),
}, (t) => [
  check('billing_events_kind_check', sql`${t.notificationKind} IN ('subscription','voided','test','unknown','malformed')`),
]);

// Informes inteligentes cacheados por huella de los datos de origen.
export const aiReports = pgTable('ai_reports', {
  id: text('id').primaryKey(),
  userId: text('user_id').notNull(),
  kind: text('kind').notNull(),
  sourceFingerprint: text('source_fingerprint').notNull(),
  payload: jsonb('payload').notNull(),
  model: text('model').notNull(),
  createdAt: bigint('created_at', { mode: 'number' }).notNull(),
}, (t) => [uniqueIndex('ai_reports_user_kind_fingerprint_idx').on(t.userId, t.kind, t.sourceFingerprint)]);

// Metadatos de cada respuesta de IA (Coach/mesociclo). `client_message_id` hace idempotente el
// reintento de un envío: nunca dos respuestas por el mismo mensaje del usuario.
export const aiResponses = pgTable('ai_responses', {
  id: text('id').primaryKey(),
  userId: text('user_id').notNull(),
  clientMessageId: text('client_message_id').notNull(),
  kind: text('kind').notNull().default('coach'),
  status: text('status').notNull(),
  // Texto final de una respuesta completa del Coach (NULL mientras está incompleta).
  content: text('content'),
  // Intento de generación vivo: solo él puede completar la fila; otro intento espera a que el
  // lease se libere o caduque.
  attemptId: text('attempt_id'),
  leaseExpiresAt: bigint('lease_expires_at', { mode: 'number' }),
  createdAt: bigint('created_at', { mode: 'number' }).notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(),
}, (t) => [
  uniqueIndex('ai_responses_user_client_message_idx').on(t.userId, t.clientMessageId),
  check('ai_responses_status_check', sql`${t.status} IN ('complete','incomplete')`),
  check('ai_responses_kind_check', sql`${t.kind} IN ('coach','mesocycle')`),
]);

// Reportes de respuestas de IA problemáticas (uno por usuario y respuesta).
export const reportedAiResponses = pgTable('reported_ai_responses', {
  id: text('id').primaryKey(),
  userId: text('user_id').notNull(),
  responseId: text('response_id').notNull(),
  category: text('category').notNull(),
  context: text('context'),
  createdAt: bigint('created_at', { mode: 'number' }).notNull(),
}, (t) => [
  uniqueIndex('reported_ai_responses_user_response_idx').on(t.userId, t.responseId),
  // Mismo enum que AI_REPORT_CATEGORIES (lib/api/v1/ai/contracts.ts).
  check('reported_ai_responses_category_check',
    sql`${t.category} IN ('inaccurate','harmful','medical','offensive','other')`),
]);

// ── Borrado de cuenta (0005). Solo servidor. ──

// Un trabajo por cuenta cuyo borrado se ha pedido. Que exista la fila ES el bloqueo de acceso
// (403 ACCOUNT_DELETING en /api/v1 y en las rutas legacy de sync/fotos). Sin contenido del usuario:
// `user_id_hash` es un SHA-256 con sal del id de Clerk y `user_id` solo se guarda mientras el trabajo
// está pendiente (hace falta para reanudar R2/Postgres/Clerk); al terminar pasa a NULL.
export const accountDeletionJobs = pgTable('account_deletion_jobs', {
  userIdHash: text('user_id_hash').primaryKey(),
  userId: text('user_id'),
  status: text('status').notNull(),
  step: text('step').notNull(),
  attempts: integer('attempts').notNull().default(0),
  lastErrorCode: text('last_error_code'),
  leaseOwner: text('lease_owner'),
  leaseExpiresAt: bigint('lease_expires_at', { mode: 'number' }), // epoch ms
  requestedAt: bigint('requested_at', { mode: 'number' }).notNull(),
  updatedAt: bigint('updated_at', { mode: 'number' }).notNull(),
  completedAt: bigint('completed_at', { mode: 'number' }),
}, (t) => [
  index('account_deletion_jobs_pending_idx').on(t.updatedAt).where(sql`${t.status} = 'pending'`),
  check('account_deletion_jobs_status_check', sql`${t.status} IN ('pending','done')`),
  check('account_deletion_jobs_step_check',
    sql`${t.step} IN ('storage','database','storage_sweep','identity','done')`),
  check('account_deletion_jobs_user_id_check', sql`(${t.status} = 'done') = (${t.userId} IS NULL)`),
]);
