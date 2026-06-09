# Cuerpo D1b — Registro de métricas (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Definir el catálogo de métricas corporales (predefinidas + personalizadas) y la resolución de su etiqueta/unidad, con las personalizadas viviendo en el store de ajustes sincronizado (C0).

**Architecture:** Un módulo puro `lib/body-metrics.ts` con `METRICAS_PREDEF` (orden + label + unidad), `slugify`, y `resolverMetrica(tipo, personalizadas)` (función pura, sin Dexie). Las métricas personalizadas se guardan en la clave `metricasPersonalizadas` del store C0 (`useSetting` reactivo en cliente; `getSetting`/`setSetting` para lógica). El CRUD mínimo (añadir) se hace con un helper que lee-modifica-escribe el array vía `getSetting`/`setSetting`.

**Tech Stack:** TypeScript · Dexie/C0 settings (`lib/use-setting.ts`, `lib/repositories/user-settings.ts`) · vitest.

**Nota:** Fase **D1b** del spec `docs/superpowers/specs/2026-06-09-seguimiento-corporal-design.md`. D1a (entidad `BodyMetric`) ya está mergeada. D1c (pantalla `/cuerpo`) consumirá este módulo.

---

## File Structure

- **Create** `lib/body-metrics.ts` — catálogo predefinido, tipos, `slugify`, `resolverMetrica` (puro), helpers de personalizadas (`addMetricaPersonalizada`).
- **Create** `lib/body-metrics.test.ts` — tests de `resolverMetrica`, `slugify` y el helper de añadir.

---

## Task 1: Catálogo predefinido + tipos + `resolverMetrica` (puro)

**Files:**
- Create: `lib/body-metrics.ts`
- Create: `lib/body-metrics.test.ts`

> Función pura, sin dependencias de Dexie. TDD estricto.

- [ ] **Step 0: READ FIRST**

Read `docs/superpowers/specs/2026-06-09-seguimiento-corporal-design.md` sección "Registro de métricas" (líneas 42-45) y `lib/repositories/user-settings.ts` (`getSetting`/`setSetting`). El spec fija: predefinidas `peso` (kg) + `cintura`, `cadera`, `pecho`, `hombros`, `biceps`, `antebrazo`, `muslo`, `pantorrilla`, `cuello` (todas cm). Personalizadas = `Array<{ clave: string; label: string; unidad: string }>` en clave `metricasPersonalizadas`. `resolverMetrica` busca predef → personalizada → fallback `{ label: tipo, unidad: '' }`.

- [ ] **Step 1: Write the failing test** — create `lib/body-metrics.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import {
  METRICAS_PREDEF,
  ORDEN_PREDEF,
  slugify,
  resolverMetrica,
  type MetricaPersonalizada,
} from '@/lib/body-metrics';

describe('METRICAS_PREDEF', () => {
  it('peso está en kg y las medidas en cm', () => {
    expect(METRICAS_PREDEF.peso).toEqual({ label: 'Peso', unidad: 'kg' });
    expect(METRICAS_PREDEF.cintura.unidad).toBe('cm');
  });
  it('ORDEN_PREDEF empieza por peso y cubre todas las claves', () => {
    expect(ORDEN_PREDEF[0]).toBe('peso');
    expect([...ORDEN_PREDEF].sort()).toEqual(Object.keys(METRICAS_PREDEF).sort());
  });
});

describe('slugify', () => {
  it('normaliza acentos, espacios y mayúsculas', () => {
    expect(slugify('Brazo Derecho')).toBe('brazo-derecho');
    expect(slugify('  Glúteo  ')).toBe('gluteo');
  });
  it('colapsa caracteres no alfanuméricos', () => {
    expect(slugify('% grasa!!')).toBe('grasa');
  });
});

describe('resolverMetrica', () => {
  const pers: MetricaPersonalizada[] = [{ clave: 'grasa', label: '% Grasa', unidad: '%' }];
  it('resuelve una predefinida', () => {
    expect(resolverMetrica('cintura', pers)).toEqual({ label: 'Cintura', unidad: 'cm' });
  });
  it('resuelve una personalizada', () => {
    expect(resolverMetrica('grasa', pers)).toEqual({ label: '% Grasa', unidad: '%' });
  });
  it('cae al fallback para una desconocida', () => {
    expect(resolverMetrica('xyz', pers)).toEqual({ label: 'xyz', unidad: '' });
  });
  it('predefinida tiene prioridad sobre personalizada con la misma clave', () => {
    expect(resolverMetrica('peso', [{ clave: 'peso', label: 'X', unidad: 'lb' }])).toEqual({
      label: 'Peso', unidad: 'kg',
    });
  });
});
```

- [ ] **Step 2: Run → FAIL**

Run: `npx vitest run lib/body-metrics.test.ts`
Expected: FAIL (module not found).

- [ ] **Step 3: Implement** — create `lib/body-metrics.ts`:

```ts
export interface MetricaDef {
  label: string;
  unidad: string;
}

export interface MetricaPersonalizada extends MetricaDef {
  clave: string;
}

/** Catálogo predefinido. peso en kg; el resto, medidas en cm. */
export const METRICAS_PREDEF: Record<string, MetricaDef> = {
  peso: { label: 'Peso', unidad: 'kg' },
  cintura: { label: 'Cintura', unidad: 'cm' },
  cadera: { label: 'Cadera', unidad: 'cm' },
  pecho: { label: 'Pecho', unidad: 'cm' },
  hombros: { label: 'Hombros', unidad: 'cm' },
  biceps: { label: 'Bíceps', unidad: 'cm' },
  antebrazo: { label: 'Antebrazo', unidad: 'cm' },
  muslo: { label: 'Muslo', unidad: 'cm' },
  pantorrilla: { label: 'Pantorrilla', unidad: 'cm' },
  cuello: { label: 'Cuello', unidad: 'cm' },
};

/** Orden de presentación en la UI (peso primero). */
export const ORDEN_PREDEF: readonly string[] = [
  'peso', 'cintura', 'cadera', 'pecho', 'hombros',
  'biceps', 'antebrazo', 'muslo', 'pantorrilla', 'cuello',
];

/** Slug estable a partir de un nombre libre: sin acentos, minúsculas, guiones. */
export function slugify(nombre: string): string {
  return nombre
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

/** Etiqueta + unidad de un tipo: predefinida → personalizada → fallback defensivo. */
export function resolverMetrica(
  tipo: string,
  personalizadas: MetricaPersonalizada[],
): MetricaDef {
  const predef = METRICAS_PREDEF[tipo];
  if (predef) return predef;
  const pers = personalizadas.find((m) => m.clave === tipo);
  if (pers) return { label: pers.label, unidad: pers.unidad };
  return { label: tipo, unidad: '' };
}
```

- [ ] **Step 4: Run → PASS**

Run: `npx vitest run lib/body-metrics.test.ts` → PASS. `npx tsc --noEmit` clean.

- [ ] **Step 5: Commit**

```bash
git add lib/body-metrics.ts lib/body-metrics.test.ts
git commit -m "feat(cuerpo): catálogo de métricas + resolverMetrica (puro)"
```

---

## Task 2: CRUD mínimo de personalizadas (`metricasPersonalizadas` en C0)

**Files:**
- Modify: `lib/body-metrics.ts`
- Modify: `lib/body-metrics.test.ts`

> Helper de añadir sobre el store C0 (read-modify-write). Test con fake-indexeddb (proyecto `app`).

- [ ] **Step 0: READ FIRST**

Read again `lib/repositories/user-settings.ts` (`getSetting<T>` devuelve `undefined` si no existe; `setSetting<T>` upsert). El array de personalizadas vive en clave `metricasPersonalizadas`. El helper `addMetricaPersonalizada(nombre, unidad)` debe: slugificar el nombre → clave; rechazar si la clave choca con una predefinida o ya existe en el array (idempotente: devolver la existente sin duplicar); si no, append y persistir; devolver la `MetricaPersonalizada` resultante.

- [ ] **Step 1: Write the failing test** — append to `lib/body-metrics.test.ts`:

```ts
import { getSetting } from '@/lib/repositories/user-settings';
import { db } from '@/lib/db/database';
import { CLAVE_PERSONALIZADAS, addMetricaPersonalizada, listMetricasPersonalizadas } from '@/lib/body-metrics';

describe('personalizadas (C0)', () => {
  it('añade una personalizada, la persiste y la devuelve', async () => {
    await db.userSettings.clear();
    const m = await addMetricaPersonalizada('% Grasa', '%');
    expect(m).toEqual({ clave: 'grasa', label: '% Grasa', unidad: '%' });
    expect(await getSetting(CLAVE_PERSONALIZADAS)).toEqual([m]);
    expect(await listMetricasPersonalizadas()).toEqual([m]);
  });
  it('es idempotente por clave (no duplica)', async () => {
    await db.userSettings.clear();
    await addMetricaPersonalizada('Glúteo', 'cm');
    const segunda = await addMetricaPersonalizada('glúteo', 'cm');
    expect(segunda.clave).toBe('gluteo');
    expect(await listMetricasPersonalizadas()).toHaveLength(1);
  });
  it('rechaza una clave que choca con una predefinida', async () => {
    await db.userSettings.clear();
    await expect(addMetricaPersonalizada('Peso', 'lb')).rejects.toThrow();
  });
});
```

- [ ] **Step 2: Run → FAIL**

Run: `npx vitest run lib/body-metrics.test.ts` → FAIL (exports no existen).

- [ ] **Step 3: Implement** — append to `lib/body-metrics.ts`:

```ts
import { getSetting, setSetting } from '@/lib/repositories/user-settings';

export const CLAVE_PERSONALIZADAS = 'metricasPersonalizadas';

/** Lista (no reactiva) de métricas personalizadas. Para UI reactiva usar useSetting. */
export async function listMetricasPersonalizadas(): Promise<MetricaPersonalizada[]> {
  return (await getSetting<MetricaPersonalizada[]>(CLAVE_PERSONALIZADAS)) ?? [];
}

/**
 * Añade una métrica personalizada (read-modify-write sobre C0).
 * Idempotente por clave; lanza si la clave choca con una predefinida.
 */
export async function addMetricaPersonalizada(
  nombre: string,
  unidad: string,
): Promise<MetricaPersonalizada> {
  const clave = slugify(nombre);
  if (!clave) throw new Error('Nombre de métrica vacío');
  if (METRICAS_PREDEF[clave]) throw new Error(`"${clave}" ya es una métrica predefinida`);
  const actuales = await listMetricasPersonalizadas();
  const existente = actuales.find((m) => m.clave === clave);
  if (existente) return existente;
  const nueva: MetricaPersonalizada = { clave, label: nombre.trim(), unidad: unidad.trim() };
  await setSetting<MetricaPersonalizada[]>(CLAVE_PERSONALIZADAS, [...actuales, nueva]);
  return nueva;
}
```

(Coloca el `import` al principio del fichero junto a los demás, no en medio.)

- [ ] **Step 4: Run → PASS**

Run: `npx vitest run lib/body-metrics.test.ts` → PASS. `npx tsc --noEmit` clean. `npx eslint lib/body-metrics.ts lib/body-metrics.test.ts` clean.

- [ ] **Step 5: Commit**

```bash
git add lib/body-metrics.ts lib/body-metrics.test.ts
git commit -m "feat(cuerpo): CRUD mínimo de métricas personalizadas (C0)"
```

---

## Task 3: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint`
Expected: todo verde.

---

## Self-Review (hecho)

- **Spec cobertura:** METRICAS_PREDEF (peso kg + 9 medidas cm) ✓; `resolverMetrica` predef→personalizada→fallback ✓; personalizadas en C0 clave `metricasPersonalizadas` como `Array<{clave,label,unidad}>` ✓; CRUD mínimo "añadir; clave = slug" ✓.
- **Tipos consistentes:** `MetricaDef`/`MetricaPersonalizada` reutilizados; `CLAVE_PERSONALIZADAS` constante única; mismo nombre de helper en test e impl.
- **Sin placeholders:** todo el código presente.
- **Decisión defensiva:** idempotencia por clave + rechazo de choque con predefinida (cubre el caso límite del spec "personalizada borrada de ajustes" desde el lado de no permitir colisiones).
