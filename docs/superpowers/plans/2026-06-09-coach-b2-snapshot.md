# Coach B2 — Snapshot de señales (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Una función que arma un "snapshot" compacto de las señales de entreno (estancamiento, semana, PRs, volumen/grupo, grupos descuidados, objetivos) que el coach IA recibe como contexto.

**Architecture:** `construirSnapshot(input)` PURA (recibe resultados ya consultados → objeto compacto, top-N, días sin entrenar, nombres legibles) + `recogerSnapshot(gymId?, now?)` async que consulta las señales de A/C y delega en la pura. El cliente (B4) llamará a `recogerSnapshot`; la ruta (B3) recibe el objeto y lo serializa al prompt.

**Tech Stack:** TypeScript · Dexie (lectura vía repos existentes) · Vitest + fake-indexeddb.

**Nota:** Fase **B2** del spec `docs/superpowers/specs/2026-06-09-coach-ia-design.md` (sección "Snapshot"). B1 (entidad) ya está en main. No toca datos/sync/schema — solo lee señales ya existentes. B3–B5 después.

Señales disponibles (firmas reales en `lib/repositories/stats.ts` / `lib/insights.ts`):
- `listEstancados(gymId?): Promise<Estancado[]>` — `Estancado { exerciseId, nombre, sesionesSinMejora, ultimaMejoraFecha }`.
- `getWeeklySummary(gymId?, now?): Promise<WeeklySummary>` — `{ sesiones, volumenSemana, volumenSemanaPrevia, deltaPct }`.
- `getPRsThisWeek(gymId?, now?): Promise<PRSemana[]>` — `{ exerciseId, nombre, tipo: 'peso'|'1rm' }`.
- `getVolumenSemanaByMuscle(gymId?, now?): Promise<Record<MuscleGroup, number>>`.
- `getLastTrainedByMuscle(gymId?): Promise<Record<MuscleGroup, number|null>>`.
- Ajustes: `getSetting<number>('objetivoSemanal')`, `getSetting<Partial<Record<MuscleGroup,number>>>('objetivosVolumen')` (de `lib/repositories/user-settings.ts`).
- `muscleGroupLabel` (de `lib/labels.ts`), `MUSCLE_GROUPS` (de `lib/db/types.ts`).

---

## File Structure

- **Create** `lib/coach-snapshot.ts` — tipos `CoachSnapshot`/`SnapshotInput`, `construirSnapshot` (pura), `recogerSnapshot` (async).
- **Create** `lib/coach-snapshot.test.ts` — tests de `construirSnapshot` (puro) + `recogerSnapshot` (integración fake-indexeddb).

---

## Task 1: `construirSnapshot` (puro) + tipos

**Files:**
- Create: `lib/coach-snapshot.ts`
- Create: `lib/coach-snapshot.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/stats.ts` for the exact shapes of `Estancado`, `WeeklySummary`, `PRSemana`; `lib/labels.ts` for `muscleGroupLabel`; `lib/db/types.ts` for `MuscleGroup`/`MUSCLE_GROUPS`.

- [ ] **Step 1: Write the failing test** — `lib/coach-snapshot.test.ts`

```ts
import { describe, it, expect } from 'vitest';
import { construirSnapshot, type SnapshotInput } from '@/lib/coach-snapshot';

const DIA = 86400000;
const AHORA = 100 * DIA;

function baseInput(): SnapshotInput {
  return {
    estancados: [],
    semana: { sesiones: 2, volumenSemana: 1234.6, volumenSemanaPrevia: 1000, deltaPct: 23 },
    objetivoSemanal: 3,
    prs: [],
    volumenSemanaPorGrupo: Object.fromEntries(['pecho','espalda','hombros','biceps','triceps','cuadriceps','femoral','gluteo','gemelo','abdomen','antebrazo','otro'].map((g) => [g, 0])) as SnapshotInput['volumenSemanaPorGrupo'],
    lastTrained: Object.fromEntries(['pecho','espalda','hombros','biceps','triceps','cuadriceps','femoral','gluteo','gemelo','abdomen','antebrazo','otro'].map((g) => [g, null])) as SnapshotInput['lastTrained'],
    objetivosVolumen: {},
    ahora: AHORA,
  };
}

describe('construirSnapshot', () => {
  it('semana: redondea volumen y copia objetivo/deltaPct/PRs', () => {
    const inp = baseInput();
    inp.prs = [{ exerciseId: 'e1', nombre: 'Press', tipo: 'peso' }];
    const s = construirSnapshot(inp);
    expect(s.semana).toEqual({
      sesiones: 2, objetivo: 3, volumen: 1235, deltaPct: 23,
      prs: [{ ejercicio: 'Press', tipo: 'peso' }],
    });
  });

  it('estancados: top 5, mapea nombre + sesionesSinMejora', () => {
    const inp = baseInput();
    inp.estancados = Array.from({ length: 7 }, (_, i) => ({
      exerciseId: `e${i}`, nombre: `Ej${i}`, sesionesSinMejora: i + 3, ultimaMejoraFecha: 0,
    }));
    const s = construirSnapshot(inp);
    expect(s.estancados).toHaveLength(5);
    expect(s.estancados[0]).toEqual({ ejercicio: 'Ej0', sesionesSinMejora: 3 });
  });

  it('grupos: incluye con volumen/objetivo/entrenado, ordena por volumen desc, días sin entrenar', () => {
    const inp = baseInput();
    inp.volumenSemanaPorGrupo.pecho = 800;
    inp.volumenSemanaPorGrupo.espalda = 500;
    inp.lastTrained.pecho = AHORA - 2 * DIA;   // hace 2 días
    inp.lastTrained.espalda = AHORA - 12 * DIA; // hace 12 días
    inp.objetivosVolumen.gluteo = 600; // sin volumen pero con objetivo → incluido
    const s = construirSnapshot(inp);
    const grupos = s.grupos.map((g) => g.grupo);
    expect(grupos.slice(0, 2)).toEqual(['Pecho', 'Espalda']); // por volumen desc (usa muscleGroupLabel)
    expect(grupos).toContain('Glúteo');
    const pecho = s.grupos.find((g) => g.grupo === 'Pecho')!;
    expect(pecho).toEqual({ grupo: 'Pecho', volumenSemana: 800, diasSinEntrenar: 2, objetivo: null });
    const gluteo = s.grupos.find((g) => g.grupo === 'Glúteo')!;
    expect(gluteo.objetivo).toBe(600);
    expect(gluteo.diasSinEntrenar).toBeNull(); // nunca entrenado
  });

  it('grupos: excluye los sin volumen/objetivo/entrenamiento; top 8', () => {
    const inp = baseInput();
    // ningún grupo tiene datos → lista vacía
    expect(construirSnapshot(inp).grupos).toHaveLength(0);
  });
});
```
(El label de `gluteo` en `muscleGroupLabel` puede ser "Glúteo" — VERIFICA en `lib/labels.ts` y ajusta el string esperado al real.)

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/coach-snapshot.test.ts`
Expected: FAIL — módulo inexistente.

- [ ] **Step 3: Implement** — `lib/coach-snapshot.ts` (parte pura)

```ts
import type { MuscleGroup } from '@/lib/db/types';
import { MUSCLE_GROUPS } from '@/lib/db/types';
import { muscleGroupLabel } from '@/lib/labels';
import type { Estancado, WeeklySummary, PRSemana } from '@/lib/repositories/stats';

const DIA = 86400000;
const MAX_ESTANCADOS = 5;
const MAX_GRUPOS = 8;

export interface SnapshotInput {
  estancados: Estancado[];
  semana: WeeklySummary;
  objetivoSemanal: number;
  prs: PRSemana[];
  volumenSemanaPorGrupo: Record<MuscleGroup, number>;
  lastTrained: Record<MuscleGroup, number | null>;
  objetivosVolumen: Partial<Record<MuscleGroup, number>>;
  ahora: number;
}

export interface CoachSnapshot {
  estancados: { ejercicio: string; sesionesSinMejora: number }[];
  semana: {
    sesiones: number;
    objetivo: number;
    volumen: number;
    deltaPct: number | null;
    prs: { ejercicio: string; tipo: 'peso' | '1rm' }[];
  };
  grupos: { grupo: string; volumenSemana: number; diasSinEntrenar: number | null; objetivo: number | null }[];
}

/** Arma el contexto compacto del coach a partir de las señales ya consultadas. Pura. */
export function construirSnapshot(input: SnapshotInput): CoachSnapshot {
  const estancados = input.estancados
    .slice(0, MAX_ESTANCADOS)
    .map((e) => ({ ejercicio: e.nombre, sesionesSinMejora: e.sesionesSinMejora }));

  const semana = {
    sesiones: input.semana.sesiones,
    objetivo: input.objetivoSemanal,
    volumen: Math.round(input.semana.volumenSemana),
    deltaPct: input.semana.deltaPct,
    prs: input.prs.map((p) => ({ ejercicio: p.nombre, tipo: p.tipo })),
  };

  const grupos = MUSCLE_GROUPS
    .map((g) => {
      const vol = input.volumenSemanaPorGrupo[g] ?? 0;
      const ult = input.lastTrained[g] ?? null;
      const objetivo = input.objetivosVolumen[g] ?? null;
      const diasSinEntrenar = ult == null ? null : Math.floor((input.ahora - ult) / DIA);
      return { grupo: muscleGroupLabel[g], volumenSemana: Math.round(vol), diasSinEntrenar, objetivo, _vol: vol, _ult: ult };
    })
    .filter((g) => g._vol > 0 || g.objetivo != null || g._ult != null)
    .sort((a, b) => b._vol - a._vol)
    .slice(0, MAX_GRUPOS)
    .map(({ _vol, _ult, ...rest }) => rest);

  return { estancados, semana, grupos };
}
```

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/coach-snapshot.test.ts`
Expected: PASS. Also `npx tsc --noEmit` clean.

- [ ] **Step 5: Commit**

```bash
git add lib/coach-snapshot.ts lib/coach-snapshot.test.ts
git commit -m "feat(coach): construirSnapshot (contexto compacto de señales, puro)"
```

---

## Task 2: `recogerSnapshot` (async, consulta señales)

**Files:**
- Modify: `lib/coach-snapshot.ts`
- Modify: `lib/coach-snapshot.test.ts`

- [ ] **Step 1: Write the failing test** — append to `lib/coach-snapshot.test.ts` (integración con fake-indexeddb; siembra historial y verifica que el snapshot refleja las señales):

```ts
import 'fake-indexeddb/auto';
import { recogerSnapshot } from '@/lib/coach-snapshot';
import { db } from '@/lib/db/database';
import { setSetting } from '@/lib/repositories/user-settings';

it('recogerSnapshot reúne las señales reales en el snapshot', async () => {
  await Promise.all([
    db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(),
    db.exercises.clear(), db.userSettings.clear(),
  ]);
  const NOW = new Date('2026-06-10T12:00:00').getTime(); // miércoles
  await db.exercises.put({ id: 'snap-ex', userId: null, nombre: 'Press banca', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });
  // sesión de esta semana, gym g1
  const s = { id: 'snap-s', userId: null, gymId: 'g1', fecha: NOW, updatedAt: 1, deletedAt: null };
  await db.workoutSessions.put(s);
  const le = { id: 'snap-le', sessionId: 'snap-s', exerciseId: 'snap-ex', orden: 0, updatedAt: 1, deletedAt: null };
  await db.loggedExercises.put(le);
  await db.loggedSets.put({ id: 'snap-set', loggedExerciseId: 'snap-le', orden: 0, peso: 50, reps: 10, updatedAt: 1, deletedAt: null });
  await setSetting('objetivoSemanal', 4);

  const snap = await recogerSnapshot('g1', NOW);
  expect(snap.semana.sesiones).toBe(1);
  expect(snap.semana.objetivo).toBe(4);
  // pecho entrenado esta semana → aparece en grupos con volumen 500 (50×10)
  const pecho = snap.grupos.find((g) => g.grupo === 'Pecho');
  expect(pecho?.volumenSemana).toBe(500);
  expect(pecho?.diasSinEntrenar).toBe(0);
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/coach-snapshot.test.ts -t "recogerSnapshot"`
Expected: FAIL — `recogerSnapshot` not exported.

- [ ] **Step 3: Implement** — append to `lib/coach-snapshot.ts`:

```ts
import {
  listEstancados, getWeeklySummary, getPRsThisWeek,
  getVolumenSemanaByMuscle, getLastTrainedByMuscle,
} from '@/lib/repositories/stats';
import { getSetting } from '@/lib/repositories/user-settings';

/** Consulta las señales de A/C (filtradas por gym) y arma el snapshot del coach. */
export async function recogerSnapshot(gymId?: string | null, now: number = Date.now()): Promise<CoachSnapshot> {
  const [estancados, semana, prs, volumenSemanaPorGrupo, lastTrained] = await Promise.all([
    listEstancados(gymId),
    getWeeklySummary(gymId, now),
    getPRsThisWeek(gymId, now),
    getVolumenSemanaByMuscle(gymId, now),
    getLastTrainedByMuscle(gymId),
  ]);
  const objetivoSemanal = (await getSetting<number>('objetivoSemanal')) ?? 3;
  const objetivosVolumen = (await getSetting<Partial<Record<MuscleGroup, number>>>('objetivosVolumen')) ?? {};
  return construirSnapshot({
    estancados, semana, objetivoSemanal, prs,
    volumenSemanaPorGrupo, lastTrained, objetivosVolumen, ahora: now,
  });
}
```
(Place the `import { listEstancados, ... }` and `getSetting` imports at the top of the file with the others.)

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/coach-snapshot.test.ts`
Expected: PASS (puro + integración). Also `npx tsc --noEmit` clean.

- [ ] **Step 5: Commit**

```bash
git add lib/coach-snapshot.ts lib/coach-snapshot.test.ts
git commit -m "feat(coach): recogerSnapshot (consulta señales A/C + delega en construirSnapshot)"
```

---

## Task 3: Verificación final

- [ ] **Step 1: Full suite**

Run: `npx vitest run`
Expected: todo verde.

- [ ] **Step 2: Types + lint**

Run: `npx tsc --noEmit && npm run lint`
Expected: sin errores.

- [ ] **Step 3: Build**

Run: `npm run build`
Expected: build OK (`--webpack`).

---

## Self-Review (hecho)

**Cobertura del spec (sección "Snapshot"):**
- `construirSnapshot` puro que reúne estancados + semana(+PRs) + volumen/grupo + descuidados + objetivos → Task 1. ✓
- Acotado (top-N por sección: estancados 5, grupos 8) → Task 1. ✓
- `recogerSnapshot` consulta las señales reales (gym-filtrado) y delega → Task 2. ✓
- Vive en `lib/coach-snapshot.ts`, testeable con mocks (pura) + integración → Tasks 1, 2. ✓
- Tests: construirSnapshot (semana/estancados/grupos/vacío) + recogerSnapshot (integración) → Tasks 1, 2. ✓

**Sin placeholders:** código completo en cada paso (salvo el label exacto de "Glúteo", que el implementador VERIFICA en labels.ts — instrucción explícita, no un hueco).

**Consistencia de tipos:** `SnapshotInput` usa los tipos reales `Estancado`/`WeeklySummary`/`PRSemana` + `Record<MuscleGroup, …>`. `construirSnapshot(input): CoachSnapshot`; `recogerSnapshot(gymId?, now?): Promise<CoachSnapshot>` (misma forma de salida). Las firmas de las señales coinciden con stats.ts (gymId?, now?). `getSetting` con las claves 'objetivoSemanal'/'objetivosVolumen' de C0/C2/C3. ✓

**Decisiones:** volumen por grupo = el de la **semana actual** (`getVolumenSemanaByMuscle`), coherente con que los objetivos de volumen son semanales (C3). Un grupo se incluye si tiene volumen, objetivo, o fue entrenado alguna vez. El snapshot es un objeto estructurado; la **serialización al prompt** se hace en B3 (la ruta).
