// One-time/idempotente: descarga las fotos por defecto del catálogo a public/catalog/<slug>.png.
// Uso: node scripts/fetch-catalog-photos.mjs [--force]
// Las URLs las aporta el usuario (una por ejercicio del catálogo seed).
import { mkdir, writeFile, access } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = join(ROOT, 'public', 'catalog');
const force = process.argv.includes('--force');

// slug del ejercicio seed (sin el prefijo "seed-") → URL de la imagen.
const FOTOS = {
  'press-banca': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Barbell-Bench-Press_0316b783-43b2-44f8-8a2b-b177a2cfcbfc_600x600.png?v=1612137800',
  'press-inclinado-mancuerna': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Incline-Dumbbell-Bench-Press_c2bf89a2-433f-4a8f-9801-67c679980867_600x600.png?v=1612138008',
  'aperturas-polea': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Cable-Crossover_09c90616-2777-47ed-927e-d5987edfce09_600x600.png?v=1612138036',
  'fondos': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Parallel-Dip-Bar_600x600.png?v=1619977962',
  'peso-muerto': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Dumbbell-Deadlift_600x600.png?v=1619976747',
  'dominadas': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Pull-Up_600x600.png?v=1619977612',
  'jalon-al-pecho': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Wide-Grip-Pulldown_91fcba9b-47a2-4185-b093-aa542c81c55c_600x600.png?v=1612138105',
  'remo-barra': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Barbell-Row_4beb1d94-bac9-4538-9578-2d9cf93ef008_600x600.png?v=1612138201',
  'remo-mancuerna': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Dumbbell-Bent-Over-Rows_600x600.png?v=1619977463',
  'press-militar': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Standing-Barbell-Shoulder-Press_600x600.png?v=1619977694',
  'elevaciones-laterales': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Dumbbell-Lateral-Raise_31c81eee-81c4-4ffe-890d-ee13dd5bbf20_600x600.png?v=1612138523',
  'pajaros': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Bent-Over-Lateral-Raise_41bd4de4-0370-4e6b-9501-37cdcc26ded4_600x600.png?v=1621163232',
  'curl-barra': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Barbell-Curl_f38580d5-412e-4082-b453-5d319afa94fd_600x600.png?v=1612137128',
  'curl-martillo': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Hammer-Curl_da9fea8b-fc81-4a4f-9af1-aea1b85239d7_600x600.png?v=1612137282',
  'extension-polea': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Triceps-Pressdown_e759437b-6200-4b44-b484-14db770024a4_600x600.png?v=1612136845',
  'press-frances': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Lying-Triceps-Extension_4affa7a2-9c1c-48f8-8003-3570d7b3a39c_600x600.png?v=1612136744',
  'sentadilla': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Squat_d752e42d-02ba-4692-b300-c6e67ad5a4f5_600x600.png?v=1612138811',
  'prensa': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Leg-Press_f7febd5c-75e5-42f4-9bb4-c938969ce293_600x600.png?v=1612138836',
  'extension-cuadriceps': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Leg-Extension_41d91d3f-4b9c-4374-82e2-1d697ce35fe4_600x600.png?v=1612138862',
  'zancada': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Lunge_600x600.png?v=1612138903',
  'curl-femoral': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Lying-Leg-Curl_203153d8-79dd-4bb9-9125-708aa4327107_600x600.png?v=1612139013',
  'peso-muerto-rumano': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Barbell-Romanian-Deadlift_34ede1b4-63ac-451d-9536-bbf9942b560c_600x600.png?v=1621162957',
  'hip-thrust': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Barbell-Hip-Thrust_600x600.png?v=1656402338',
  'elevacion-talones': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Standing-Calf-Raise_61746b47-98aa-49ee-bb97-5a19562592b9_600x600.png?v=1612137090',
  'crunch': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Crunch_f3498d5d-82d9-4a7f-8dee-98a2e55a62f2_600x600.png?v=1612138317',
  'plancha': 'https://cdn.shopify.com/s/files/1/0269/5551/3900/files/Plank_3a82d566-9cb2-4c20-b301-bc8bd635c4d1_600x600.png?v=1612138431',
};

async function existe(p) {
  try { await access(p); return true; } catch { return false; }
}

async function descargar(slug, url) {
  const dest = join(OUT, `${slug}.png`);
  if (!force && (await existe(dest))) return { slug, estado: 'existe' };
  for (let intento = 1; intento <= 3; intento++) {
    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const buf = Buffer.from(await res.arrayBuffer());
      await writeFile(dest, buf);
      return { slug, estado: 'ok', kb: Math.round(buf.length / 1024) };
    } catch (e) {
      if (intento === 3) return { slug, estado: `fallo: ${e.message}` };
      await new Promise((r) => setTimeout(r, 500 * intento));
    }
  }
}

await mkdir(OUT, { recursive: true });
const slugs = Object.keys(FOTOS);
let ok = 0, kbTotal = 0;
for (const slug of slugs) {
  const r = await descargar(slug, FOTOS[slug]);
  if (r.estado === 'ok') { ok++; kbTotal += r.kb; }
  console.log(`  ${r.slug.padEnd(28)} ${r.estado}${r.kb ? ` (${r.kb} KB)` : ''}`);
}
console.log(`\n${ok}/${slugs.length} descargadas, ${Math.round(kbTotal)} KB en total → public/catalog/`);
