import { enforceRateLimit } from '@/lib/api/v1/rate-limit';

/**
 * Cupo de IA de un usuario para una llamada a /api/v1/ai/*: el límite por minuto ('ai') y el
 * presupuesto diario ('aiDaily', 200/día). Cualquiera agotado → 429 RATE_LIMITED con Retry-After.
 * El diario solo se gasta si el de minuto lo permite.
 */
export async function enforceAiBudget(userId: string): Promise<void> {
  await enforceRateLimit('ai', userId);
  await enforceRateLimit('aiDaily', userId);
}
