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

/** Medios privados v1: no necesitan URL pública, solo credenciales y bucket. */
export function r2PrivateConfigured(): boolean {
  return Boolean(
    process.env.R2_ACCOUNT_ID && process.env.R2_ACCESS_KEY_ID && process.env.R2_SECRET_ACCESS_KEY && process.env.R2_BUCKET,
  );
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

// ── Medios privados v1 ──
// El objeto solo es legible con una URL GET firmada y breve; nunca se construye una URL pública.

export async function putPrivateObject(key: string, body: Uint8Array, contentType: string): Promise<void> {
  await client().send(
    new PutObjectCommand({
      Bucket: process.env.R2_BUCKET!,
      Key: key,
      Body: body,
      ContentType: contentType,
      CacheControl: 'private, max-age=300',
    }),
  );
}

/** Firma local (SigV4, sin red) de una lectura que caduca en `ttlSeconds`. */
export async function signPrivateRead(key: string, ttlSeconds: number): Promise<string> {
  return getSignedUrl(client(), new GetObjectCommand({ Bucket: process.env.R2_BUCKET!, Key: key }), {
    expiresIn: ttlSeconds,
  });
}

/** Idempotente: R2/S3 responde éxito aunque el objeto ya no exista. */
export async function deletePrivateObject(key: string): Promise<void> {
  await deleteR2Object(key);
}

/** Implementación R2 del puerto `MediaStorage` del servicio de medios. */
export const r2MediaStorage = {
  putPrivate: putPrivateObject,
  signRead: signPrivateRead,
  remove: deletePrivateObject,
};
