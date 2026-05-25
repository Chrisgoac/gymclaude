/** Render 3D por defecto de cada ejercicio del catálogo (en /public/catalog). */
export const CATALOG_PHOTOS: Record<string, string> = {
  'seed-press-banca': '/catalog/press-banca.jpg',
  'seed-press-inclinado-mancuerna': '/catalog/press-inclinado-mancuerna.jpg',
  'seed-aperturas-polea': '/catalog/aperturas-polea.jpg',
  'seed-fondos': '/catalog/fondos.jpg',
  'seed-dominadas': '/catalog/dominadas.jpg',
  'seed-jalon-al-pecho': '/catalog/jalon-al-pecho.jpg',
  'seed-remo-barra': '/catalog/remo-barra.jpg',
  'seed-remo-mancuerna': '/catalog/remo-mancuerna.jpg',
  'seed-peso-muerto': '/catalog/peso-muerto.jpg',
  'seed-press-militar': '/catalog/press-militar.jpg',
  'seed-elevaciones-laterales': '/catalog/elevaciones-laterales.jpg',
  'seed-pajaros': '/catalog/pajaros.jpg',
  'seed-curl-barra': '/catalog/curl-barra.jpg',
  'seed-curl-martillo': '/catalog/curl-martillo.jpg',
  'seed-extension-polea': '/catalog/extension-polea.jpg',
  'seed-press-frances': '/catalog/press-frances.jpg',
  'seed-sentadilla': '/catalog/sentadilla.jpg',
  'seed-prensa': '/catalog/prensa.jpg',
  'seed-extension-cuadriceps': '/catalog/extension-cuadriceps.jpg',
  'seed-zancada': '/catalog/zancada.jpg',
  'seed-curl-femoral': '/catalog/curl-femoral.jpg',
  'seed-peso-muerto-rumano': '/catalog/peso-muerto-rumano.jpg',
  'seed-hip-thrust': '/catalog/hip-thrust.jpg',
  'seed-elevacion-talones': '/catalog/elevacion-talones.jpg',
  'seed-crunch': '/catalog/crunch.jpg',
  'seed-plancha': '/catalog/plancha.jpg',
};

/** URL de imagen a mostrar: foto del usuario > render por defecto del catálogo > undefined. */
export function resolveExercisePhotoUrl(exerciseId: string, userPhotoUrl?: string): string | undefined {
  return userPhotoUrl ?? CATALOG_PHOTOS[exerciseId];
}
