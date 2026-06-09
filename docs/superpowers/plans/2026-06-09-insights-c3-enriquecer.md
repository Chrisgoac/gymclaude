# Insights C3 — Enriquecer mapa muscular + volumen (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enriquecer Progreso con tres cosas: grupos musculares descuidados (días sin entrenar), comparativa de volumen vs la semana previa, y objetivo de volumen por grupo muscular.

**Architecture:** Una función de stats (`getLastTrainedByMuscle`) y un helper puro (`weeklyVolumeDeltas`) sobre datos existentes; un ajuste sincronizado `objetivosVolumen` (useSetting, de C0). UI: `MuscleBalance` gana anotación "hace N días" + resaltado de descuidados + marca de meta; `WeeklyVolumeChart` muestra el % por barra; Ajustes gana inputs de meta por grupo. No toca datos/sync/schema.

**Tech Stack:** Next.js 16 + React 19 + TypeScript · Dexie · Recharts · Vitest + fake-indexeddb.

**Nota:** Fase **C3** del spec `docs/superpowers/specs/2026-06-08-insights-design.md` (módulo 3). C0/C1/C2 ya están en `main` y desplegados. `MUSCLE_GROUPS` y `muscleGroupLabel` ya existen. Todo derivado de datos existentes — sin cambios de Dexie/Drizzle/sync/backup.

---

## File Structure

- **Modify** `lib/repositories/stats.ts` — `getLastTrainedByMuscle(gymId?)` + `weeklyVolumeDeltas(points)` (puro) + tipo `WeeklyVolumeDelta`.
- **Modify** `lib/repositories/stats.test.ts` — tests de ambas.
- **Modify** `components/muscle-balance.tsx` — anotación "hace N días" + resaltado descuidados + marca de meta.
- **Modify** `components/weekly-volume-chart.tsx` — etiqueta de % por barra.
- **Modify** `app/progreso/page.tsx` — pasar `lastTrained` y `objetivos` a `MuscleBalance`.
- **Modify** `app/ajustes/page.tsx` — sección "Objetivo de volumen por grupo".

---

## Task 1: `getLastTrainedByMuscle` + `weeklyVolumeDeltas`

**Files:**
- Modify: `lib/repositories/stats.ts`
- Modify: `lib/repositories/stats.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/stats.ts`: `activo`, `db`, the gym-filter idiom and the exercise→grupoMuscular mapping in `getVolumeByMuscle` (bulkGet exercises → grupoBy map), the `WeeklyVolumePoint` interface, and confirm `MUSCLE_GROUPS` is importable from `@/lib/db/types`. Read `lib/repositories/stats.test.ts` seeding style (the `NOW_WED`/`DAY` consts exist at top level).

- [ ] **Step 1: Write the failing tests** — append to `lib/repositories/stats.test.ts`:

```ts
import { getLastTrainedByMuscle, weeklyVolumeDeltas } from '@/lib/repositories/stats';

it('getLastTrainedByMuscle devuelve la última fecha por grupo y null si nunca', async () => {
  await db.exercises.put({ id: 'lt-pecho', userId: null, nombre: 'P', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });
  const s1 = { id: 'lt-s1', userId: null, gymId: 'glt', fecha: 1000, updatedAt: 1, deletedAt: null };
  const s2 = { id: 'lt-s2', userId: null, gymId: 'glt', fecha: 5000, updatedAt: 1, deletedAt: null };
  await db.workoutSessions.bulkPut([s1, s2]);
  await db.loggedExercises.bulkPut([
    { id: 'lt-le1', sessionId: 'lt-s1', exerciseId: 'lt-pecho', orden: 0, updatedAt: 1, deletedAt: null },
    { id: 'lt-le2', sessionId: 'lt-s2', exerciseId: 'lt-pecho', orden: 0, updatedAt: 1, deletedAt: null },
  ]);
  const r = await getLastTrainedByMuscle('glt');
  expect(r.pecho).toBe(5000); // la más reciente
  expect(r.gemelo).toBeNull(); // nunca entrenado
});

it('weeklyVolumeDeltas calcula el % vs la barra anterior (primera = null)', () => {
  const out = weeklyVolumeDeltas([
    { semanaInicioTs: 1, volumen: 100 },
    { semanaInicioTs: 2, volumen: 150 },
    { semanaInicioTs: 3, volumen: 75 },
  ]);
  expect(out[0].deltaPct).toBeNull();
  expect(out[1].deltaPct).toBe(50);   // 100→150
  expect(out[2].deltaPct).toBe(-50);  // 150→75
});

it('weeklyVolumeDeltas deltaPct null si la semana previa tuvo 0 volumen', () => {
  const out = weeklyVolumeDeltas([
    { semanaInicioTs: 1, volumen: 0 },
    { semanaInicioTs: 2, volumen: 100 },
  ]);
  expect(out[1].deltaPct).toBeNull();
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/repositories/stats.test.ts -t "getLastTrainedByMuscle"`
Expected: FAIL — not exported.

- [ ] **Step 3: Implement** — in `lib/repositories/stats.ts`:

1. Ensure `MuscleGroup`/`MUSCLE_GROUPS` are imported from `@/lib/db/types` (add to the existing type import if missing).
2. Add:

```ts
/** Timestamp (epoch ms) de la última sesión que entrenó cada grupo muscular; null si nunca (en ese gym). */
export async function getLastTrainedByMuscle(gymId?: string | null): Promise<Record<MuscleGroup, number | null>> {
  const result = Object.fromEntries(MUSCLE_GROUPS.map((g) => [g, null])) as Record<MuscleGroup, number | null>;
  const sessions = activo(await db.workoutSessions.toArray())
    .filter((s) => gymId == null || (s.gymId ?? null) === gymId);
  const fechaBy = new Map(sessions.map((s) => [s.id, s.fecha]));
  const sessionIds = new Set(sessions.map((s) => s.id));
  if (sessionIds.size === 0) return result;
  const les = activo(await db.loggedExercises.toArray()).filter((le) => sessionIds.has(le.sessionId));
  const exerciseIds = [...new Set(les.map((le) => le.exerciseId))];
  const exercises = await db.exercises.bulkGet(exerciseIds);
  const grupoBy = new Map<string, MuscleGroup>();
  for (const e of exercises) if (e) grupoBy.set(e.id, e.grupoMuscular);
  for (const le of les) {
    const grupo = grupoBy.get(le.exerciseId);
    if (!grupo) continue;
    const fecha = fechaBy.get(le.sessionId) ?? 0;
    if (result[grupo] == null || fecha > (result[grupo] as number)) result[grupo] = fecha;
  }
  return result;
}

export interface WeeklyVolumeDelta extends WeeklyVolumePoint {
  deltaPct: number | null;
}

/** Anota cada punto de volumen semanal con el % de cambio vs la semana anterior (primera = null). */
export function weeklyVolumeDeltas(points: WeeklyVolumePoint[]): WeeklyVolumeDelta[] {
  return points.map((p, i) => {
    if (i === 0) return { ...p, deltaPct: null };
    const prev = points[i - 1].volumen;
    const deltaPct = prev > 0 ? Math.round(((p.volumen - prev) / prev) * 100) : null;
    return { ...p, deltaPct };
  });
}
```
Match the real `activo`/`MuscleGroup` names.

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/repositories/stats.test.ts`
Expected: PASS (new + existing). Also `npx tsc --noEmit` clean.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/stats.ts lib/repositories/stats.test.ts
git commit -m "feat(insights): getLastTrainedByMuscle + weeklyVolumeDeltas"
```

---

## Task 2: Grupos descuidados en MuscleBalance

**Files:**
- Modify: `components/muscle-balance.tsx`
- Modify: `app/progreso/page.tsx`

> UI sobre función ya testeada; verificación por tsc/lint.

- [ ] **Step 0: READ FIRST**

Read `components/muscle-balance.tsx` (current: takes `data: VolumeByMuscle[]`, renders a bar per group sorted by volume desc). Read `app/progreso/page.tsx` (the `volumen` live query feeding `<MuscleBalance data={volumen ?? []} />`, and `gymId`).

- [ ] **Step 1: Rewrite the component** — `components/muscle-balance.tsx`:

```tsx
import type { VolumeByMuscle } from '@/lib/repositories/stats';
import type { MuscleGroup } from '@/lib/db/types';
import { MUSCLE_GROUPS } from '@/lib/db/types';
import { muscleGroupLabel } from '@/lib/labels';

const DIA = 86400000;
const UMBRAL_DESCUIDADO_DIAS = 10;

export function MuscleBalance({
  data,
  lastTrained,
}: {
  data: VolumeByMuscle[];
  lastTrained?: Record<MuscleGroup, number | null>;
}) {
  const volByGrupo = new Map(data.map((d) => [d.grupo, d.volumen]));
  // Grupos a mostrar: con volumen en el periodo, o (si tenemos lastTrained) entrenados alguna vez → así afloran los descuidados.
  const grupos = MUSCLE_GROUPS
    .filter((g) => volByGrupo.has(g) || (lastTrained ? lastTrained[g] != null : false))
    .sort((a, b) => (volByGrupo.get(b) ?? 0) - (volByGrupo.get(a) ?? 0));
  if (grupos.length === 0) return <p className="text-muted-foreground">Aún no hay volumen registrado.</p>;
  const max = Math.max(...grupos.map((g) => volByGrupo.get(g) ?? 0), 1);
  const ahora = Date.now();
  return (
    <div className="space-y-2">
      {grupos.map((g) => {
        const vol = volByGrupo.get(g) ?? 0;
        const ult = lastTrained?.[g] ?? null;
        const dias = ult == null ? null : Math.floor((ahora - ult) / DIA);
        const descuidado = lastTrained != null && (ult == null || (dias as number) > UMBRAL_DESCUIDADO_DIAS);
        return (
          <div key={g}>
            <div className="label-mono mb-1 flex items-center justify-between text-[10px] text-muted-foreground">
              <span className={descuidado ? 'text-destructive' : ''}>{muscleGroupLabel[g]}</span>
              <span className={descuidado ? 'text-destructive' : ''}>
                {Math.round(vol)} kg·rep
                {lastTrained != null && ` · ${ult == null ? 'nunca' : `hace ${dias}d`}`}
              </span>
            </div>
            <div className="h-3.5 border-2 border-foreground bg-card">
              <div className="h-full bg-primary" style={{ width: `${(vol / max) * 100}%` }} />
            </div>
          </div>
        );
      })}
    </div>
  );
}
```
(Backward compatible: without `lastTrained`, the filter keeps only groups with volume — same rows as before, no annotation.)

- [ ] **Step 2: Wire `lastTrained` in Progreso** — in `app/progreso/page.tsx`:
1. Add imports:
```ts
import { getLastTrainedByMuscle } from '@/lib/repositories/stats';
```
2. Add a live query:
```ts
const lastTrained = useLiveQuery(() => getLastTrainedByMuscle(gymId), [gymId]);
```
3. Pass it to the existing MuscleBalance usage:
```tsx
<MuscleBalance data={volumen ?? []} lastTrained={lastTrained} />
```

- [ ] **Step 3: Typecheck + lint + suite**

Run: `npx tsc --noEmit && npx eslint components/muscle-balance.tsx app/progreso/page.tsx && npx vitest run`
Expected: clean/green.

- [ ] **Step 4: Commit**

```bash
git add components/muscle-balance.tsx app/progreso/page.tsx
git commit -m "feat(insights): grupos descuidados (hace N días + resaltado) en mapa muscular"
```

---

## Task 3: Comparativa de volumen vs semana previa

**Files:**
- Modify: `components/weekly-volume-chart.tsx`

> Consume el helper puro `weeklyVolumeDeltas` (Task 1). La página sigue pasando `WeeklyVolumePoint[]`; el chart calcula los deltas internamente.

- [ ] **Step 0: READ FIRST**

Read `components/weekly-volume-chart.tsx` (Recharts BarChart over `data: WeeklyVolumePoint[]`, maps to `{ semana, volumen }`).

- [ ] **Step 1: Implement** — rewrite `components/weekly-volume-chart.tsx` to add a per-bar delta label via Recharts `LabelList`:

```tsx
'use client';

import { BarChart, Bar, XAxis, YAxis, Tooltip, LabelList, ResponsiveContainer } from 'recharts';
import type { WeeklyVolumePoint } from '@/lib/repositories/stats';
import { weeklyVolumeDeltas } from '@/lib/repositories/stats';

export function WeeklyVolumeChart({ data }: { data: WeeklyVolumePoint[] }) {
  const barras = weeklyVolumeDeltas(data).map((p) => ({
    semana: new Date(p.semanaInicioTs).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit' }),
    volumen: p.volumen,
    delta: p.deltaPct == null ? '' : `${p.deltaPct >= 0 ? '▲' : '▼'}${Math.abs(p.deltaPct)}%`,
  }));
  return (
    <div className="h-48 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={barras} margin={{ top: 16, right: 8, bottom: 0, left: -16 }}>
          <XAxis dataKey="semana" fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <YAxis fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <Tooltip
            cursor={{ fill: 'currentColor', fillOpacity: 0.08 }}
            contentStyle={{ border: '2px solid currentColor', borderRadius: 0, fontFamily: 'var(--font-mono)', fontSize: 12 }}
          />
          <Bar dataKey="volumen" className="text-primary" fill="currentColor" stroke="currentColor">
            <LabelList dataKey="delta" position="top" fontSize={10} fill="currentColor" />
          </Bar>
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
```
(`margin.top` raised 8→16 to give the labels room.)

- [ ] **Step 2: Typecheck + lint + suite**

Run: `npx tsc --noEmit && npx eslint components/weekly-volume-chart.tsx && npx vitest run`
Expected: clean/green.

- [ ] **Step 3: Commit**

```bash
git add components/weekly-volume-chart.tsx
git commit -m "feat(insights): % de cambio por barra en volumen semanal"
```

---

## Task 4: Ajuste "Objetivo de volumen por grupo"

**Files:**
- Modify: `app/ajustes/page.tsx`

- [ ] **Step 0: READ FIRST**

Read `app/ajustes/page.tsx` (the "Objetivos" section added in C2, the uncontrolled-input pattern, and that `useSetting` is imported). Read `lib/repositories/user-settings.ts` (`getSetting`/`setSetting`) and confirm `MUSCLE_GROUPS`/`muscleGroupLabel` import paths.

- [ ] **Step 1: Implement** — in `app/ajustes/page.tsx`:

1. Add imports (merge with existing):
```ts
import { MUSCLE_GROUPS, type MuscleGroup } from '@/lib/db/types';
import { muscleGroupLabel } from '@/lib/labels';
import { getSetting, setSetting } from '@/lib/repositories/user-settings';
```
2. In the component:
```ts
const [objetivosVolumen] = useSetting<Partial<Record<MuscleGroup, number>>>('objetivosVolumen', {});
// Read-modify-write contra Dexie al escribir (no del render) para no perder ediciones en filas distintas.
const aplicarObjetivoVolumen = (g: MuscleGroup, v: number | null) => {
  void (async () => {
    const cur = (await getSetting<Partial<Record<MuscleGroup, number>>>('objetivosVolumen')) ?? {};
    const next = { ...cur };
    if (v == null) delete next[g]; else next[g] = v;
    await setSetting('objetivosVolumen', next);
  })();
};
```
3. Add a section (after the "Objetivos" / weekly-goal section). Render one compact input per muscle group; empty clears the goal:
```tsx
<section className="space-y-3">
  <h2 className="label-mono text-[11px] text-muted-foreground">Objetivo de volumen por grupo</h2>
  <div className="brutal-box divide-y-2 divide-foreground">
    {MUSCLE_GROUPS.map((g) => (
      <label key={g} className="flex items-center justify-between gap-3 px-3 py-2">
        <span className="text-sm">{muscleGroupLabel[g]}</span>
        <input
          type="number"
          min="0"
          step="100"
          key={`${g}-${objetivosVolumen[g] ?? ''}`}
          defaultValue={objetivosVolumen[g] ?? ''}
          className="w-24 border-2 border-input bg-card p-1 text-right text-sm tabular-nums"
          onBlur={(e) => {
            const raw = e.target.value.trim();
            if (raw === '') { aplicarObjetivoVolumen(g, null); return; }
            const n = Math.max(0, Math.round(Number(raw)));
            if (Number.isNaN(n)) { e.target.value = String(objetivosVolumen[g] ?? ''); return; }
            aplicarObjetivoVolumen(g, n === 0 ? null : n);
          }}
        />
      </label>
    ))}
  </div>
  <p className="label-mono text-[10px] text-muted-foreground">Volumen objetivo (kg·rep) por grupo y semana. Vacío = sin meta.</p>
</section>
```

- [ ] **Step 2: Typecheck + lint + suite**

Run: `npx tsc --noEmit && npx eslint app/ajustes/page.tsx && npx vitest run`
Expected: clean/green.

- [ ] **Step 3: Commit**

```bash
git add app/ajustes/page.tsx
git commit -m "feat(insights): ajuste objetivo de volumen por grupo (sincronizado)"
```

---

## Task 5: Marca de meta de volumen en MuscleBalance

**Files:**
- Modify: `components/muscle-balance.tsx`
- Modify: `app/progreso/page.tsx`

- [ ] **Step 0: READ FIRST**

Re-read the current `components/muscle-balance.tsx` (after Task 2). Read how `app/progreso/page.tsx` reads settings — it does NOT yet read `objetivosVolumen`; you'll add a `useSetting` there.

- [ ] **Step 1: Add the `objetivos` prop + marker** — in `components/muscle-balance.tsx`:

1. Add `MuscleGroup`-keyed `objetivos` to the props:
```tsx
export function MuscleBalance({
  data,
  lastTrained,
  objetivos,
}: {
  data: VolumeByMuscle[];
  lastTrained?: Record<MuscleGroup, number | null>;
  objetivos?: Partial<Record<MuscleGroup, number>>;
}) {
```
2. Inside the `grupos.map`, after computing `vol`, compute the goal and render a marker + % inside the bar. Replace the bar `<div>` block with a `relative` container holding the fill plus an absolute marker when a goal exists:
```tsx
        const meta = objetivos?.[g];
        const metaPct = meta && meta > 0 ? Math.min(100, Math.round((vol / meta) * 100)) : null;
        const markerLeft = meta && meta > 0 ? Math.min(100, (meta / max) * 100) : null;
        return (
          <div key={g}>
            <div className="label-mono mb-1 flex items-center justify-between text-[10px] text-muted-foreground">
              <span className={descuidado ? 'text-destructive' : ''}>{muscleGroupLabel[g]}</span>
              <span className={descuidado ? 'text-destructive' : ''}>
                {Math.round(vol)} kg·rep
                {metaPct != null && ` · ${metaPct}% meta`}
                {lastTrained != null && ` · ${ult == null ? 'nunca' : `hace ${dias}d`}`}
              </span>
            </div>
            <div className="relative h-3.5 border-2 border-foreground bg-card">
              <div className="h-full bg-primary" style={{ width: `${(vol / max) * 100}%` }} />
              {markerLeft != null && (
                <div className="absolute inset-y-0 w-0.5 bg-foreground" style={{ left: `${markerLeft}%` }} aria-hidden="true" />
              )}
            </div>
          </div>
        );
```
Keep the rest of the component (the `grupos` filter, `max`, `descuidado`, `dias`, `ult`) unchanged.

- [ ] **Step 2: Wire `objetivos` in Progreso** — in `app/progreso/page.tsx`:
1. Add imports:
```ts
import { useSetting } from '@/lib/use-setting';
import type { MuscleGroup } from '@/lib/db/types';
```
2. Read the setting:
```ts
const [objetivosVolumen] = useSetting<Partial<Record<MuscleGroup, number>>>('objetivosVolumen', {});
```
3. Pass to MuscleBalance:
```tsx
<MuscleBalance data={volumen ?? []} lastTrained={lastTrained} objetivos={objetivosVolumen} />
```

- [ ] **Step 3: Typecheck + lint + suite**

Run: `npx tsc --noEmit && npx eslint components/muscle-balance.tsx app/progreso/page.tsx && npx vitest run`
Expected: clean/green.

- [ ] **Step 4: Commit**

```bash
git add components/muscle-balance.tsx app/progreso/page.tsx
git commit -m "feat(insights): marca de meta de volumen por grupo en el mapa muscular"
```

---

## Task 6: Verificación final

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

**Cobertura del spec (módulo 3):**
- Grupos descuidados: `getLastTrainedByMuscle(gymId?)` + MuscleBalance "hace N días"/"nunca" + resaltado >10 días → Tasks 1, 2. ✓
- Comparativa volumen vs semana previa: `weeklyVolumeDeltas` (puro) + etiqueta % por barra → Tasks 1, 3. ✓
- Objetivo de volumen por grupo: ajuste `objetivosVolumen` (useSetting, sincronizado) + UI en Ajustes + marca de meta en MuscleBalance → Tasks 4, 5. ✓
- Tests: getLastTrainedByMuscle (última/null) + weeklyVolumeDeltas (delta/primera-null/previa-0) → Task 1. ✓

**Sin placeholders:** código completo en cada paso.

**Consistencia de tipos:** `getLastTrainedByMuscle` devuelve `Record<MuscleGroup, number|null>` consumido por MuscleBalance `lastTrained`. `weeklyVolumeDeltas` → `WeeklyVolumeDelta[]` consumido por WeeklyVolumeChart. `objetivosVolumen: Partial<Record<MuscleGroup, number>>` con la misma clave en Ajustes (escritura) y Progreso/MuscleBalance (lectura). `useSetting`/`getSetting`/`setSetting` de C0. MuscleBalance se amplía en Task 2 (lastTrained) y Task 5 (objetivos) de forma aditiva y retrocompatible. ✓

**Decisiones conscientes:** "descuidado" solo se evalúa cuando se pasa `lastTrained` (retrocompat); umbral 10 días. La meta por grupo es `kg·rep`/semana; vacío o 0 = sin meta. El marcador de meta se posiciona relativo al `max` de la vista (igual que las barras). `getLastTrainedByMuscle` cuenta un grupo como entrenado si hay un loggedExercise de ese grupo en una sesión (no exige sets) — coherente con "ese día entrenaste ese grupo".
