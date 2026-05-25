import { mkdir, writeFile, stat } from 'node:fs/promises';
import { join } from 'node:path';

// slug (sin 'seed-') → descripción en inglés para el prompt.
const PROMPTS = {
  'press-banca': 'barbell bench press',
  'press-inclinado-mancuerna': 'incline dumbbell chest press',
  'aperturas-polea': 'cable chest fly',
  'fondos': 'parallel bar chest dips',
  'dominadas': 'pull-ups on a bar',
  'jalon-al-pecho': 'lat pulldown machine',
  'remo-barra': 'barbell bent-over row',
  'remo-mancuerna': 'one-arm dumbbell row on bench',
  'peso-muerto': 'barbell deadlift',
  'press-militar': 'standing barbell overhead shoulder press',
  'elevaciones-laterales': 'dumbbell lateral raise',
  'pajaros': 'bent-over dumbbell reverse fly',
  'curl-barra': 'standing barbell biceps curl',
  'curl-martillo': 'dumbbell hammer curl',
  'extension-polea': 'cable triceps pushdown',
  'press-frances': 'lying barbell skull crusher triceps extension',
  'sentadilla': 'barbell back squat',
  'prensa': 'leg press machine',
  'extension-cuadriceps': 'leg extension machine',
  'zancada': 'dumbbell walking lunge',
  'curl-femoral': 'lying leg curl machine',
  'peso-muerto-rumano': 'romanian deadlift with barbell',
  'hip-thrust': 'barbell hip thrust',
  'elevacion-talones': 'standing calf raise machine',
  'crunch': 'abdominal crunch on floor',
  'plancha': 'plank exercise hold',
};

const STYLE =
  'clean 3D render, neutral grey mannequin figure, plain light studio background, isometric view, centered, full body, no text, no watermark';
const OUT_DIR = 'public/catalog';
const SEED = 42;

async function existeNoVacio(path) {
  try {
    const s = await stat(path);
    return s.size > 0;
  } catch {
    return false;
  }
}

async function generar(slug, descripcion) {
  const out = join(OUT_DIR, `${slug}.jpg`);
  if (await existeNoVacio(out)) {
    console.log(`= ${slug} (ya existe, salto)`);
    return true;
  }
  const prompt = `${descripcion}, ${STYLE}`;
  const url = `https://image.pollinations.ai/prompt/${encodeURIComponent(prompt)}?width=600&height=600&model=flux&nologo=true&seed=${SEED}`;
  for (let intento = 1; intento <= 3; intento++) {
    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const buf = Buffer.from(await res.arrayBuffer());
      if (buf.length < 1000) throw new Error('respuesta demasiado pequeña');
      await writeFile(out, buf);
      console.log(`✓ ${slug} (${(buf.length / 1024).toFixed(0)} KB)`);
      return true;
    } catch (e) {
      console.log(`… ${slug} intento ${intento} falló: ${e.message}`);
      await new Promise((r) => setTimeout(r, 2000 * intento));
    }
  }
  console.log(`✗ ${slug} NO generado`);
  return false;
}

const okSlugs = [];
await mkdir(OUT_DIR, { recursive: true });
for (const [slug, desc] of Object.entries(PROMPTS)) {
  if (await generar(slug, desc)) okSlugs.push(slug);
}
console.log('\n=== slugs OK (' + okSlugs.length + '/' + Object.keys(PROMPTS).length + ') ===');
console.log(okSlugs.map((s) => `'seed-${s}': '/catalog/${s}.jpg',`).join('\n'));
