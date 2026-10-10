import { z } from 'zod';

/** Record id: client-generated or catalog/setting key (e.g. `seed-...`, `restSeconds`). */
export const RecordIdSchema = z
  .string()
  .min(1)
  .max(128)
  .regex(/^[A-Za-z0-9._:-]+$/);

/** PostgreSQL text rejects U+0000: refuse it at the edge (400) instead of failing the insert. */
export const hasNul = (value: unknown): boolean => {
  if (typeof value === 'string') return value.includes('\u0000');
  if (Array.isArray(value)) return value.some(hasNul);
  if (value !== null && typeof value === 'object') {
    return Object.entries(value).some(([k, v]) => hasNul(k) || hasNul(v));
  }
  return false;
};
const NUL_MESSAGE = 'Must not contain U+0000';

/** Text field: any string without U+0000. */
export const TextSchema = z.string().refine((s) => !hasNul(s), NUL_MESSAGE);
/** Arbitrary JSON value without U+0000 in any string or key. */
export const JsonValueSchema = z.json().refine((v) => !hasNul(v), NUL_MESSAGE);
/** PostgreSQL `integer` (int32). */
export const Int32Schema = z.number().int().min(-2147483648).max(2147483647);

export const MutationIdSchema = z.uuid();
export const DeviceIdSchema = TextSchema.min(1).max(128);

/** Integer milliseconds within JS safe-integer range. */
export const EpochMsSchema = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);

/** PostgreSQL bigint serialized as base-10 string. */
export const ServerVersionSchema = z.string().regex(/^(0|[1-9][0-9]{0,18})$/);

export const ApiErrorBodySchema = z
  .object({
    error: z
      .object({
        code: z.string().min(1),
        message: z.string(),
        retryable: z.boolean(),
        details: z.record(z.string(), z.array(z.string())).optional(),
      })
      .strict(),
  })
  .strict();

export type ApiErrorBody = z.infer<typeof ApiErrorBodySchema>;
