import { z } from 'zod';
import { EpochMsSchema, RecordIdSchema, TextSchema } from '@/lib/api/v1/contracts/common';

// Contratos de medios privados v1. La clave del objeto la genera SIEMPRE el servidor
// (`users/<userHash>/<kind>/<uuid>`); el cliente solo manda bytes, MIME declarado y metadatos.

export const MEDIA_KINDS = ['exercise', 'progress'] as const;
export const MediaKindSchema = z.enum(MEDIA_KINDS);
export type MediaKind = z.infer<typeof MediaKindSchema>;

/** Tipos que acepta el servidor. La PWA y Android comprimen a JPEG; PNG/WebP por compatibilidad. */
export const ALLOWED_MEDIA_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;
export type AllowedMediaType = (typeof ALLOWED_MEDIA_TYPES)[number];

export const MEDIA_LIMITS = {
  /** Tamaño comprimido máximo (igual que las rutas legacy de la PWA). */
  maxBytes: 8 * 1024 * 1024,
  /** Lado máximo decodificado, en píxeles. */
  maxDimension: 8192,
  /** Píxeles decodificados máximos (ancho × alto). */
  maxPixels: 40_000_000,
  /** Vida de una URL de lectura firmada. Es transporte, nunca se persiste. */
  readUrlTtlSeconds: 300,
} as const;

export const PROGRESS_ANGLES = ['frente', 'lado', 'espalda'] as const;

/** Campos de formulario de una subida de foto de ejercicio. `id` opcional = id del registro local. */
export const ExerciseMediaFieldsSchema = z
  .object({ id: RecordIdSchema.optional(), exerciseId: RecordIdSchema })
  .strict();
export type ExerciseMediaFields = z.infer<typeof ExerciseMediaFieldsSchema>;

/** Campos de formulario de una subida de foto de progreso. */
export const ProgressMediaFieldsSchema = z
  .object({
    id: RecordIdSchema.optional(),
    fecha: EpochMsSchema,
    angulo: z.enum(PROGRESS_ANGLES),
    nota: TextSchema.max(2000).nullable().optional(),
  })
  .strict();
export type ProgressMediaFields = z.infer<typeof ProgressMediaFieldsSchema>;

export const MediaUploadResponseSchema = z
  .object({
    mediaId: RecordIdSchema,
    objectKey: z.string().min(1),
    readUrl: z.url(),
    readUrlExpiresAt: EpochMsSchema,
  })
  .strict();
export type MediaUploadResponse = z.infer<typeof MediaUploadResponseSchema>;

export const MediaReadGrantSchema = z
  .object({ mediaId: RecordIdSchema, readUrl: z.url(), expiresAt: EpochMsSchema })
  .strict();
export type MediaReadGrant = z.infer<typeof MediaReadGrantSchema>;

export const MediaDeleteResponseSchema = z.object({ mediaId: RecordIdSchema, deleted: z.literal(true) }).strict();
export type MediaDeleteResponse = z.infer<typeof MediaDeleteResponseSchema>;
