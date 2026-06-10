import { evaluarLogros } from '@/lib/logros';
import { getLogroMetricas } from '@/lib/repositories/stats';
import { listAchievements, unlockAchievement } from '@/lib/repositories/achievements';

/**
 * Desbloquea (persistente) los hitos que se cumplen ahora y aún no estaban registrados.
 * Idempotente. Devuelve las claves recién desbloqueadas. `objetivo` = objetivo semanal.
 */
export async function reconciliarLogros(objetivo: number, now: number = Date.now()): Promise<string[]> {
  const metricas = await getLogroMetricas(objetivo, now);
  const cumplidos = evaluarLogros(metricas);
  const yaDesbloqueados = new Set((await listAchievements()).map((a) => a.clave));
  const nuevas = cumplidos.filter((c) => !yaDesbloqueados.has(c));
  for (const clave of nuevas) await unlockAchievement(clave);
  return nuevas;
}
