import { deepseek } from '@ai-sdk/deepseek';

/** Modelo DeepSeek rápido/barato (V3 chat). Cambiar aquí para usar otro. */
export const MODELO_COACH = 'deepseek-chat';

/** Provider DeepSeek listo para streamText({ model: modeloCoach(), ... }). */
export function modeloCoach() {
  return deepseek(MODELO_COACH);
}

/** true si la API key de DeepSeek está configurada (solo servidor). */
export function deepseekConfigured(): boolean {
  return Boolean(process.env.DEEPSEEK_API_KEY);
}
