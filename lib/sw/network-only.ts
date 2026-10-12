// Rutas que el service worker NUNCA cachea: respuestas privadas por usuario (redirecciones a URLs
// firmadas de medios y la API v1). Sin esto, `defaultCache` de Serwist podría guardar en Cache
// Storage una foto privada o un 302 con una URL firmada y servirla tras cerrar sesión.

/** Prefijos same-origin que van siempre a red. */
export const NETWORK_ONLY_PATH_PREFIXES = ['/api/media/read/', '/api/v1/'] as const;

/** Matcher puro (sin dependencias del worker) para poder probarlo fuera del SW. */
export function isNetworkOnlyRequest({ sameOrigin, url }: { sameOrigin: boolean; url: URL }): boolean {
  return sameOrigin && NETWORK_ONLY_PATH_PREFIXES.some((prefix) => url.pathname.startsWith(prefix));
}
