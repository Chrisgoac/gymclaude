// Límites del contrato v1. Viven aquí (no en los route.ts) porque un route handler de Next solo
// puede exportar campos de ruta conocidos.

/** Tamaño de página por defecto y máximo del pull: un `limit` mayor se recorta, no se rechaza. */
export const MAX_PULL_LIMIT = 500;

/** Tope del cuerpo de un push (50 mutaciones × 50 operaciones caben de sobra en registros reales). */
export const MAX_PUSH_BODY_BYTES = 2 * 1024 * 1024;
