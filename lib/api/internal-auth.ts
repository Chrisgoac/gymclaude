import { createHash, timingSafeEqual } from 'node:crypto';

const digest = (value: string) => createHash('sha256').update(value).digest();

/**
 * Autoriza una llamada interna (cron de Vercel) por `Authorization: Bearer $CRON_SECRET`. La
 * comparación es de tiempo constante (sobre los SHA-256, que igualan longitudes). Sin CRON_SECRET
 * configurado nunca autoriza.
 */
export function isAuthorizedCronRequest(request: Request): boolean {
  const secret = process.env.CRON_SECRET;
  if (!secret) return false;
  const header = request.headers.get('authorization') ?? '';
  return timingSafeEqual(digest(header), digest(`Bearer ${secret}`));
}
