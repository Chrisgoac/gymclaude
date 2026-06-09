# Cuerpo D1d — Integración con el snapshot del coach (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el coach (B) reciba el peso corporal y las medidas clave (último valor + cambio ~4 semanas) en su snapshot, para poder razonar sobre recomposición corporal.

**Architecture:** Se extiende `CoachSnapshot` con un campo `cuerpo`. `construirSnapshot` (pura) calcula, a partir de series por tipo ya ordenadas asc, el valor actual y el `delta4sem` (cambio vs la entrada más antigua dentro de la ventana de 28 días). `recogerSnapshot` consulta las entradas corporales (`listAllMetrics`), las agrupa por tipo, resuelve etiquetas con `resolverMetrica` y arma la entrada. El system prompt ya serializa el snapshot completo como JSON → no cambia.

**Tech Stack:** TypeScript puro + Dexie (`lib/repositories/body.ts`) + C0 settings · vitest.

**Nota:** Fase **D1d** (última de D1) del spec `docs/superpowers/specs/2026-06-09-seguimiento-corporal-design.md` (sección "Integración con el coach"). D1a–D1c ya mergeadas. Disponibles: `listAllMetrics()` (activas, asc), `resolverMetrica(tipo, personalizadas)`, `CLAVE_PERSONALIZADAS`, tipo `MetricaPersonalizada`, `BodyMetric`.

**Semántica de `delta4sem`:** ventana = `[ahora - 28*DIA, ahora]`. `actual` = valor de la última entrada (global). Referencia = la entrada **más antigua dentro de la ventana**. `delta4sem = actual - referencia.valor`, o `null` si hay menos de 2 entradas dentro de la ventana. "Medidas clave" = tipos con datos distintos de `peso`, ordenados por recencia (fecha de su última entrada) desc, top 6.

---

## File Structure

- **Modify** `lib/coach-snapshot.ts` — `CoachSnapshot.cuerpo`, `SnapshotInput.cuerpo`, lógica en `construirSnapshot`, consulta en `recogerSnapshot`.
- **Modify** `lib/coach-snapshot.test.ts` — tests de la sección `cuerpo` (puro) + ampliación del test de integración.
- **Modify** `components/coach-chat.test.tsx` — el fixture `SNAP` necesita `cuerpo` para satisfacer el tipo `CoachSnapshot`.

---

## Task 1: `cuerpo` en `CoachSnapshot` + `construirSnapshot` (puro)

**Files:**
- Modify: `lib/coach-snapshot.ts`
- Modify: `lib/coach-snapshot.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/coach-snapshot.ts` (estructura actual de `CoachSnapshot`/`SnapshotInput`/`construirSnapshot`; constante `DIA = 86400000`) y `lib/coach-snapshot.test.ts` (helper `baseInput()`, `AHORA = 100*DIA`). El nuevo input `cuerpo` son series por tipo ya ordenadas asc por fecha; `construirSnapshot` calcula actual + delta4sem y separa peso de medidas.

- [ ] **Step 1: Write the failing test** — append to `lib/coach-snapshot.test.ts` (dentro del `describe('construirSnapshot', …)` cierra en línea 64; añade estos `it` justo antes del cierre `});` del describe, y añade el campo `cuerpo: []` a `baseInput()`):

1. Add `cuerpo: [],` to the object returned by `baseInput()` (so existing tests keep compiling).

2. Add these tests inside the `describe('construirSnapshot')` block:

```ts
  it('cuerpo: sin datos → peso null y medidas vacías', () => {
    const s = construirSnapshot(baseInput());
    expect(s.cuerpo).toEqual({ peso: null, medidas: [] });
  });

  it('cuerpo: peso con actual + delta4sem (vs más antiguo en ventana 4 sem)', () => {
    const inp = baseInput();
    inp.cuerpo = [
      {
        tipo: 'peso',
        label: 'Peso',
        entradas: [
          { valor: 82, fecha: AHORA - 40 * DIA }, // fuera de ventana → ignorada para delta
          { valor: 80, fecha: AHORA - 20 * DIA }, // referencia (más antigua en ventana)
          { valor: 78, fecha: AHORA - 2 * DIA },  // actual
        ],
      },
    ];
    const s = construirSnapshot(inp);
    expect(s.cuerpo.peso).toEqual({ actual: 78, delta4sem: -2 });
    expect(s.cuerpo.medidas).toHaveLength(0);
  });

  it('cuerpo: una sola entrada en ventana → delta4sem null', () => {
    const inp = baseInput();
    inp.cuerpo = [{ tipo: 'peso', label: 'Peso', entradas: [{ valor: 80, fecha: AHORA - 1 * DIA }] }];
    expect(construirSnapshot(inp).cuerpo.peso).toEqual({ actual: 80, delta4sem: null });
  });

  it('cuerpo: medidas clave ordenadas por recencia, top 6', () => {
    const inp = baseInput();
    inp.cuerpo = Array.from({ length: 8 }, (_, i) => ({
      tipo: `m${i}`,
      label: `M${i}`,
      entradas: [{ valor: 30 + i, fecha: AHORA - i * DIA }], // m0 la más reciente
    }));
    const s = construirSnapshot(inp);
    expect(s.cuerpo.medidas).toHaveLength(6);
    expect(s.cuerpo.medidas[0].metrica).toBe('M0');
    expect(s.cuerpo.medidas[0]).toEqual({ metrica: 'M0', actual: 30, delta4sem: null });
  });
```

- [ ] **Step 2: Run → FAIL**

Run: `npx vitest run lib/coach-snapshot.test.ts` → FAIL (cuerpo no existe / tipo).

- [ ] **Step 3: Implement** — in `lib/coach-snapshot.ts`:

1. Add constants near the top (after `MAX_GRUPOS`):
```ts
const VENTANA_CUERPO = 28 * DIA;
const MAX_MEDIDAS = 6;
```

2. Add to `SnapshotInput` (after `ahora: number;`):
```ts
  /** Series corporales por tipo, entradas ya ordenadas asc por fecha. */
  cuerpo: { tipo: string; label: string; entradas: { valor: number; fecha: number }[] }[];
```

3. Add to `CoachSnapshot` (after the `grupos` field):
```ts
  cuerpo: {
    peso: { actual: number; delta4sem: number | null } | null;
    medidas: { metrica: string; actual: number; delta4sem: number | null }[];
  };
```

4. Add a pure helper (above `construirSnapshot`):
```ts
/** actual = última entrada; delta4sem vs la más antigua dentro de la ventana (null si <2 en ventana). */
function resumenCorporal(
  entradas: { valor: number; fecha: number }[],
  ahora: number,
): { actual: number; delta4sem: number | null } | null {
  if (entradas.length === 0) return null;
  const actual = entradas[entradas.length - 1].valor;
  const enVentana = entradas.filter((e) => e.fecha >= ahora - VENTANA_CUERPO);
  const delta4sem = enVentana.length >= 2 ? actual - enVentana[0].valor : null;
  return { actual, delta4sem };
}
```

5. Inside `construirSnapshot`, build `cuerpo` before the `return`, and add it to the returned object:
```ts
  const pesoSerie = input.cuerpo.find((c) => c.tipo === 'peso');
  const peso = pesoSerie ? resumenCorporal(pesoSerie.entradas, input.ahora) : null;

  const medidas = input.cuerpo
    .filter((c) => c.tipo !== 'peso' && c.entradas.length > 0)
    .sort((a, b) => b.entradas[b.entradas.length - 1].fecha - a.entradas[a.entradas.length - 1].fecha)
    .slice(0, MAX_MEDIDAS)
    .map((c) => {
      const r = resumenCorporal(c.entradas, input.ahora)!;
      return { metrica: c.label, actual: r.actual, delta4sem: r.delta4sem };
    });

  const cuerpo = { peso, medidas };
```
Then change `return { estancados, semana, grupos };` → `return { estancados, semana, grupos, cuerpo };`.

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` clean.

Note: `tsc` will now flag `recogerSnapshot` (it calls `construirSnapshot` without `cuerpo`) — that's fixed in Task 2. If running tsc before Task 2 errors only on that line, proceed; otherwise temporarily it's expected. Better: do Task 2 in the same session right after. To keep this task's tsc green, also add `cuerpo: []` to the `construirSnapshot({...})` call inside `recogerSnapshot` as a placeholder now (Task 2 replaces it).

- [ ] **Step 5: Commit**

```bash
git add lib/coach-snapshot.ts lib/coach-snapshot.test.ts
git commit -m "feat(coach): cuerpo (peso + medidas, delta4sem) en construirSnapshot"
```

---

## Task 2: `recogerSnapshot` consulta las entradas corporales

**Files:**
- Modify: `lib/coach-snapshot.ts`
- Modify: `lib/coach-snapshot.test.ts`
- Modify: `components/coach-chat.test.tsx`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/body.ts` (`listAllMetrics()` → activas asc), `lib/body-metrics.ts` (`resolverMetrica(tipo, personalizadas)`, `CLAVE_PERSONALIZADAS`, `MetricaPersonalizada`), and the current `recogerSnapshot` in `lib/coach-snapshot.ts`. También `components/coach-chat.test.tsx` (el fixture `SNAP` que pasa a `recogerSnapshot.mockResolvedValue`).

- [ ] **Step 1: Write the failing test** — append to `lib/coach-snapshot.test.ts` (after the existing integration test):

```ts
import { resolverMetrica } from '@/lib/body-metrics'; // (ya importado indirectamente; añade si falta)

it('recogerSnapshot incluye peso y medidas corporales', async () => {
  await Promise.all([
    db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(),
    db.exercises.clear(), db.userSettings.clear(), db.bodyMetrics.clear(),
  ]);
  const NOW = new Date('2026-06-10T12:00:00').getTime();
  const DIAMS = 86400000;
  await db.bodyMetrics.bulkPut([
    { id: 'p1', userId: null, tipo: 'peso', valor: 80, fecha: NOW - 20 * DIAMS, updatedAt: 1, deletedAt: null },
    { id: 'p2', userId: null, tipo: 'peso', valor: 78, fecha: NOW - 1 * DIAMS, updatedAt: 1, deletedAt: null },
    { id: 'c1', userId: null, tipo: 'cintura', valor: 85, fecha: NOW - 2 * DIAMS, updatedAt: 1, deletedAt: null },
  ]);
  const snap = await recogerSnapshot('g1', NOW);
  expect(snap.cuerpo.peso).toEqual({ actual: 78, delta4sem: -2 });
  expect(snap.cuerpo.medidas.map((m) => m.metrica)).toContain('Cintura');
});
```

- [ ] **Step 2: Run → FAIL** (cuerpo vacío porque `recogerSnapshot` aún no consulta bodyMetrics).

- [ ] **Step 3: Implement** — in `lib/coach-snapshot.ts`:

1. Add imports at the top:
```ts
import { listAllMetrics } from '@/lib/repositories/body';
import { resolverMetrica, CLAVE_PERSONALIZADAS, type MetricaPersonalizada } from '@/lib/body-metrics';
```

2. In `recogerSnapshot`, add `listAllMetrics()` and the personalizadas setting to the `Promise.all`:
```ts
  const [estancados, semana, prs, volumenSemanaPorGrupo, lastTrained, objetivoSemanalRaw, objetivosVolumenRaw, bodyMetrics, personalizadasRaw] = await Promise.all([
    listEstancados(gymId),
    getWeeklySummary(gymId, now),
    getPRsThisWeek(gymId, now),
    getVolumenSemanaByMuscle(gymId, now),
    getLastTrainedByMuscle(gymId),
    getSetting<number>('objetivoSemanal'),
    getSetting<Partial<Record<MuscleGroup, number>>>('objetivosVolumen'),
    listAllMetrics(),
    getSetting<MetricaPersonalizada[]>(CLAVE_PERSONALIZADAS),
  ]);
```

3. Group bodyMetrics by tipo (input ya viene asc por fecha desde `listAllMetrics`), resolving labels:
```ts
  const personalizadas = personalizadasRaw ?? [];
  const porTipo = new Map<string, { valor: number; fecha: number }[]>();
  for (const m of bodyMetrics) {
    const arr = porTipo.get(m.tipo) ?? [];
    arr.push({ valor: m.valor, fecha: m.fecha });
    porTipo.set(m.tipo, arr);
  }
  const cuerpo = [...porTipo.entries()].map(([tipo, entradas]) => ({
    tipo,
    label: resolverMetrica(tipo, personalizadas).label,
    entradas,
  }));
```

4. Pass `cuerpo` into `construirSnapshot({ … })` (replace the placeholder `cuerpo: []` from Task 1).

- [ ] **Step 4: Fix the coach-chat fixture** — in `components/coach-chat.test.tsx`, the `SNAP` const must satisfy `CoachSnapshot`. Add the `cuerpo` field:
```ts
const SNAP = { estancados: [], semana: { sesiones: 0, objetivo: 3, volumen: 0, deltaPct: null, prs: [] }, grupos: [], cuerpo: { peso: null, medidas: [] } };
```

- [ ] **Step 5: Run → PASS**

Run: `npx vitest run lib/coach-snapshot.test.ts components/coach-chat.test.tsx` → PASS. `npx tsc --noEmit` clean. `npx eslint lib/coach-snapshot.ts` clean.

- [ ] **Step 6: Commit**

```bash
git add lib/coach-snapshot.ts lib/coach-snapshot.test.ts components/coach-chat.test.tsx
git commit -m "feat(coach): recogerSnapshot incluye peso + medidas corporales"
```

---

## Task 3: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde.

---

## Self-Review (hecho)

- **Spec cobertura (sección "Integración con el coach"):** `CoachSnapshot.cuerpo` con `peso { actual, delta4sem } | null` + `medidas [{ metrica, actual, delta4sem }]` ✓; `construirSnapshot` calcula actual + delta vs ~4 semanas (más antiguo dentro de ventana) ✓; medidas clave = las que tienen datos, top-N por recencia ✓; `recogerSnapshot` consulta `listMetrics`/`listAllMetrics` y resuelve etiquetas ✓; sin datos → `peso null`/`medidas []` ✓; el prompt ya serializa el snapshot → sin cambio ✓.
- **Casos límite:** una sola entrada en ventana → `delta4sem null` ✓; entrada fuera de la ventana de 28 días no cuenta como referencia ✓; métrica personalizada → label vía `resolverMetrica` (fallback si borrada) ✓.
- **Tipos consistentes:** `MetricaPersonalizada` reutilizado; `cuerpo` input shape ↔ `construirSnapshot` ↔ output; fixture `SNAP` del chat actualizado para compilar.
- **Sin placeholders:** todo el código presente. La nota de tsc en Task 1 evita un estado intermedio roto (placeholder `cuerpo: []` sustituido en Task 2).
