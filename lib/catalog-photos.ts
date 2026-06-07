// Fotos por defecto del catálogo: viajan con la app (public/catalog/<slug>.png),
// así las ve todo el mundo sin tocar R2 ni el sync. La foto que sube el usuario
// (ExercisePhoto) tiene prioridad sobre la de por defecto.
// Para regenerar/añadir imágenes: editar scripts/fetch-catalog-photos.mjs y re-correr.

export const CATALOG_PHOTOS: Record<string, string> = {
  'seed-press-banca': '/catalog/press-banca.png',
  'seed-press-inclinado-mancuerna': '/catalog/press-inclinado-mancuerna.png',
  'seed-aperturas-polea': '/catalog/aperturas-polea.png',
  'seed-fondos': '/catalog/fondos.png',
  'seed-dominadas': '/catalog/dominadas.png',
  'seed-jalon-al-pecho': '/catalog/jalon-al-pecho.png',
  'seed-remo-barra': '/catalog/remo-barra.png',
  'seed-remo-mancuerna': '/catalog/remo-mancuerna.png',
  'seed-peso-muerto': '/catalog/peso-muerto.png',
  'seed-press-militar': '/catalog/press-militar.png',
  'seed-elevaciones-laterales': '/catalog/elevaciones-laterales.png',
  'seed-pajaros': '/catalog/pajaros.png',
  'seed-curl-barra': '/catalog/curl-barra.png',
  'seed-curl-martillo': '/catalog/curl-martillo.png',
  'seed-extension-polea': '/catalog/extension-polea.png',
  'seed-press-frances': '/catalog/press-frances.png',
  'seed-sentadilla': '/catalog/sentadilla.png',
  'seed-prensa': '/catalog/prensa.png',
  'seed-extension-cuadriceps': '/catalog/extension-cuadriceps.png',
  'seed-zancada': '/catalog/zancada.png',
  'seed-curl-femoral': '/catalog/curl-femoral.png',
  'seed-peso-muerto-rumano': '/catalog/peso-muerto-rumano.png',
  'seed-hip-thrust': '/catalog/hip-thrust.png',
  'seed-elevacion-talones': '/catalog/elevacion-talones.png',
  'seed-crunch': '/catalog/crunch.png',
  'seed-plancha': '/catalog/plancha.png',
};

/** URL de foto a mostrar: la del usuario si existe, si no la por defecto del catálogo, si no nada. */
export function resolveExercisePhotoUrl(exerciseId: string, userPhotoUrl?: string): string | undefined {
  return userPhotoUrl ?? CATALOG_PHOTOS[exerciseId];
}
