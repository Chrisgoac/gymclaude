import { S3Client, PutObjectCommand, DeleteObjectCommand, GetObjectCommand } from '@aws-sdk/client-s3';
import { getSignedUrl } from '@aws-sdk/s3-request-presigner';

/** Legacy PWA: subida con URL pública permanente (requiere R2_PUBLIC_URL). */
export function r2Configured(): boolean {
  return Boolean(
    process.env.R2_ACCOUNT_ID &&
      process.env.R2_ACCESS_KEY_ID &&
      process.env.R2_SECRET_ACCESS_KEY &&
      process.env.R2_BUCKET &&
      process.env.R2_PUBLIC_URL,
  );
}

/**
 * Medios privados v1: bucket propio y SIN acceso público (`R2_PRIVATE_BUCKET`), separado del bucket
 * público legacy de la PWA. Sin él, las rutas v1 de medios fallan cerradas (503) en vez de escribir
 * en el bucket público.
 */
export function r2PrivateConfigured(): boolean {
  return Boolean(
    process.env.R2_ACCOUNT_ID &&
      process.env.R2_ACCESS_KEY_ID &&
      process.env.R2_SECRET_ACCESS_KEY &&
      process.env.R2_PRIVATE_BUCKET,
  );
}

function privateBucket(): string {
  const bucket = process.env.R2_PRIVATE_BUCKET;
  if (!bucket) throw new Error('R2_PRIVATE_BUCKET is not configured');
  return bucket;
}

let cached: S3Client | null = null;
function client(): S3Client {
  if (!cached) {
    cached = new S3Client({
      region: 'auto',
      endpoint: `https://${process.env.R2_ACCOUNT_ID}.r2.cloudflarestorage.com`,
      credentials: {
        accessKeyId: process.env.R2_ACCESS_KEY_ID!,
        secretAccessKey: process.env.R2_SECRET_ACCESS_KEY!,
      },
    });
  }
  return cached;
}

/** Seam para tests: descarta el cliente cacheado (p. ej. tras cambiar variables de entorno). */
export function resetR2ClientForTests(): void {
  cached = null;
}

export async function putImage(key: string, body: Uint8Array, contentType: string): Promise<void> {
  await client().send(
    new PutObjectCommand({ Bucket: process.env.R2_BUCKET!, Key: key, Body: body, ContentType: contentType }),
  );
}

export async function deleteR2Object(key: string): Promise<void> {
  await client().send(new DeleteObjectCommand({ Bucket: process.env.R2_BUCKET!, Key: key }));
}

export function publicUrl(key: string): string {
  return `${process.env.R2_PUBLIC_URL}/${key}`;
}

// ── Medios privados v1 (bucket `R2_PRIVATE_BUCKET`) ──
// El objeto solo es legible con una URL GET firmada y breve; nunca se construye una URL pública.

export async function putPrivateObject(key: string, body: Uint8Array, contentType: string): Promise<void> {
  await client().send(
    new PutObjectCommand({
      Bucket: privateBucket(),
      Key: key,
      Body: body,
      ContentType: contentType,
      CacheControl: 'private, max-age=300',
      ContentDisposition: 'inline',
    }),
  );
}

/** Firma local (SigV4, sin red) de una lectura que caduca en `ttlSeconds`. */
export async function signPrivateRead(key: string, ttlSeconds: number): Promise<string> {
  return getSignedUrl(client(), new GetObjectCommand({ Bucket: privateBucket(), Key: key }), {
    expiresIn: ttlSeconds,
  });
}

/** Idempotente: R2/S3 responde éxito aunque el objeto ya no exista. */
export async function deletePrivateObject(key: string): Promise<void> {
  await client().send(new DeleteObjectCommand({ Bucket: privateBucket(), Key: key }));
}

/** Borra un objeto legacy de la PWA (`<userId>/…`) del bucket público `R2_BUCKET`. Idempotente. */
export async function deleteLegacyObject(key: string): Promise<void> {
  if (!process.env.R2_BUCKET) throw new Error('R2_BUCKET is not configured');
  await deleteR2Object(key);
}

/** Implementación R2 del puerto `MediaStorage` del servicio de medios. */
export const r2MediaStorage = {
  putPrivate: putPrivateObject,
  signRead: signPrivateRead,
  remove: deletePrivateObject,
  removeLegacy: deleteLegacyObject,
};
