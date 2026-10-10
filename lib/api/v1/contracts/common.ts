import { z } from 'zod';

/** Record id: client-generated or catalog/setting key (e.g. `seed-...`, `restSeconds`). */
export const RecordIdSchema = z
  .string()
  .min(1)
  .max(128)
  .regex(/^[A-Za-z0-9._:-]+$/);

export const MutationIdSchema = z.uuid();
export const DeviceIdSchema = z.string().min(1).max(128);

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
