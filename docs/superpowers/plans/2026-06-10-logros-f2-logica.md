# Logros F2 — Lógica (catálogo + métricas + rachas + reconciliación) (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** El catálogo de hitos + la evaluación pura, los stats agregados (métricas, racha semanal, lista de PRs) y la reconciliación que persiste los desbloqueos. Sin UI.

**Architecture:** `lib/logros.ts` (catálogo + `evaluarLogros` + `calcularRacha`, puros) + helpers en `lib/repositories/stats.ts` (`getRachaSemanal`, `getLogroMetricas`, `listPRs`, derivados del historial) + `lib/reconciliar-logros.ts` (`reconciliarLogros` que desbloquea con `unlockAchievement` de F1).

**Tech Stack:** TypeScript puro + Dexie (lectura) + vitest (proyectos `app` jsdom + funciones puras).

**Nota:** Fase **F2** del spec `docs/superpowers/specs/2026-06-10-logros-rachas-design.md`. F1 (entidad `Achievement` + repo `listAchievements`/`unlockAchievement`) ya mergeada. La UI es F3. `inicioSemana(ts)` es privada en `stats.ts` (los stats nuevos van ahí y la usan); `calcularRacha` (puro) recibe el inicio de la semana actual como parámetro.

**Reutiliza** de `stats.ts`: `activo`, `inicioSemana`, `estimar1RM`, patrón de agregación de `getWeeklyVolume`/`getExercisePRs`.

---

## File Structure

- **Create** `lib/logros.ts` — tipos `LogroMetricas`/`LogroDef` + `LOGROS_DEF` + `evaluarLogros` + `calcularRacha`.
- **Create** `lib/logros.test.ts`.
- **Modify** `lib/repositories/stats.ts` — `getRachaSemanal`, `getLogroMetricas`, `listPRs` (+ tipo `PRItem`).
- **Modify** `lib/repositories/stats.test.ts` (o crear si no existe).
- **Create** `lib/reconciliar-logros.ts` — `reconciliarLogros`.
- **Create** `lib/reconciliar-logros.test.ts`.

---

## Task 1: Catálogo + `evaluarLogros` + `calcularRacha` (puros)

**Files:**
- Create: `lib/logros.ts`
- Create: `lib/logros.test.ts`

- [ ] **Step 0: READ FIRST**

Read el spec sección "Catálogo de hitos" y "Lógica". `calcularRacha` recibe semanas (con su `inicioTs` y nº de `sesiones`) ya existentes, el `objetivo`, y el `inicioTs` de la semana actual; cuenta semanas **consecutivas** (índice de semana = `Math.round(inicioTs/(7*DIA))`, robusto ante DST) que cumplen `sesiones >= objetivo`. La "actual" cuenta hacia atrás desde la semana actual; si la semana actual aún no cumple (en curso), arranca desde la previa (no la rompe).

- [ ] **Step 1: Write the failing test** — create `lib/logros.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { LOGROS_DEF, evaluarLogros, calcularRacha, type LogroMetricas } from '@/lib/logros';

const DIA = 86400000;
const SEMANA = 7 * DIA;
const base: LogroMetricas = { sesionesTotales: 0, volumenTotal: 0, prsTotales: 0, mejorRacha: 0, mesociclosCompletados: 0 };

describe('evaluarLogros', () => {
  it('cumple los hitos cuyos umbrales se alcanzan', () => {
    const claves = evaluarLogros({ ...base, sesionesTotales: 55, volumenTotal: 120000, mejorRacha: 4, mesociclosCompletados: 1 });
    expect(claves).toContain('sesiones-10');
    expect(claves).toContain('sesiones-50');
    expect(claves).not.toContain('sesiones-100');
    expect(claves).toContain('volumen-100k');
    expect(claves).toContain('racha-4');
    expect(claves).not.toContain('racha-8');
    expect(claves).toContain('mesociclo-1');
  });
  it('sin datos no cumple ninguno', () => {
    expect(evaluarLogros(base)).toEqual([]);
  });
  it('todas las claves del catálogo son únicas', () => {
    const claves = LOGROS_DEF.map((d) => d.clave);
    expect(new Set(claves).size).toBe(claves.length);
  });
});

describe('calcularRacha', () => {
  const W = (n: number) => n * SEMANA; // inicio de la semana n
  it('racha actual cuenta semanas consecutivas cumpliendo, hacia atrás', () => {
    const semanas = [
      { inicioTs: W(1), sesiones: 3 },
      { inicioTs: W(2), sesiones: 4 },
      { inicioTs: W(3), sesiones: 3 }, // actual
    ];
    expect(calcularRacha(semanas, 3, W(3))).toEqual({ actual: 3, mejor: 3 });
  });
  it('la semana actual en curso por debajo del objetivo no rompe la racha', () => {
    const semanas = [
      { inicioTs: W(1), sesiones: 3 },
      { inicioTs: W(2), sesiones: 3 },
      { inicioTs: W(3), sesiones: 1 }, // actual, aún por debajo
    ];
    expect(calcularRacha(semanas, 3, W(3))).toEqual({ actual: 2, mejor: 2 });
  });
  it('un hueco rompe la racha; mejor = la racha histórica más larga', () => {
    const semanas = [
      { inicioTs: W(1), sesiones: 3 },
      { inicioTs: W(2), sesiones: 3 },
      { inicioTs: W(3), sesiones: 1 }, // no cumple (hueco)
      { inicioTs: W(4), sesiones: 3 }, // actual
    ];
    expect(calcularRacha(semanas, 3, W(4))).toEqual({ actual: 1, mejor: 2 });
  });
  it('sin semanas → 0/0', () => {
    expect(calcularRacha([], 3, W(1))).toEqual({ actual: 0, mejor: 0 });
  });
});
```

- [ ] **Step 2: Run → FAIL** (`npx vitest run lib/logros.test.ts`).

- [ ] **Step 3: Implement** — create `lib/logros.ts`:

```ts
const DIA = 86400000;
const SEMANA = 7 * DIA;

export interface LogroMetricas {
  sesionesTotales: number;
  volumenTotal: number;
  prsTotales: number;
  mejorRacha: number;
  mesociclosCompletados: number;
}

export interface LogroDef {
  clave: string;
  titulo: string;
  descripcion: string;
  criterio: (m: LogroMetricas) => boolean;
}

export const LOGROS_DEF: LogroDef[] = [
  { clave: 'sesiones-10', titulo: 'Calentando', descripcion: '10 entrenos', criterio: (m) => m.sesionesTotales >= 10 },
  { clave: 'sesiones-50', titulo: 'Constante', descripcion: '50 entrenos', criterio: (m) => m.sesionesTotales >= 50 },
  { clave: 'sesiones-100', titulo: 'Centurión', descripcion: '100 entrenos', criterio: (m) => m.sesionesTotales >= 100 },
  { clave: 'sesiones-250', titulo: 'Veterano', descripcion: '250 entrenos', criterio: (m) => m.sesionesTotales >= 250 },
  { clave: 'volumen-100k', titulo: '100K', descripcion: '100.000 kg movidos', criterio: (m) => m.volumenTotal >= 100_000 },
  { clave: 'volumen-500k', titulo: 'Medio millón', descripcion: '500.000 kg movidos', criterio: (m) => m.volumenTotal >= 500_000 },
  { clave: 'volumen-1m', titulo: 'Una tonelada x1000', descripcion: '1.000.000 kg movidos', criterio: (m) => m.volumenTotal >= 1_000_000 },
  { clave: 'racha-4', titulo: 'Racha de 4', descripcion: '4 semanas seguidas cumpliendo el objetivo', criterio: (m) => m.mejorRacha >= 4 },
  { clave: 'racha-8', titulo: 'Racha de 8', descripcion: '8 semanas seguidas cumpliendo el objetivo', criterio: (m) => m.mejorRacha >= 8 },
  { clave: 'racha-12', titulo: 'Racha de 12', descripcion: '12 semanas seguidas cumpliendo el objetivo', criterio: (m) => m.mejorRacha >= 12 },
  { clave: 'mesociclo-1', titulo: 'Planificador', descripcion: 'Completa tu primer mesociclo', criterio: (m) => m.mesociclosCompletados >= 1 },
];

/** Claves de los hitos cuyo criterio se cumple con las métricas dadas. Puro. */
export function evaluarLogros(m: LogroMetricas): string[] {
  return LOGROS_DEF.filter((d) => d.criterio(m)).map((d) => d.clave);
}

/**
 * Racha (actual + mejor) de semanas consecutivas cumpliendo el objetivo de sesiones.
 * `semanas` = conteo de sesiones por semana ISO (con su inicioTs). `inicioSemanaActual` = lunes de la semana en curso.
 * La semana actual en curso por debajo del objetivo no rompe la racha (se cuenta desde la previa). Puro.
 */
export function calcularRacha(
  semanas: { inicioTs: number; sesiones: number }[],
  objetivo: number,
  inicioSemanaActual: number,
): { actual: number; mejor: number } {
  const wk = (ts: number) => Math.round(ts / SEMANA);
  const cumple = new Set<number>();
  for (const s of semanas) if (s.sesiones >= objetivo) cumple.add(wk(s.inicioTs));

  // mejor: racha consecutiva más larga
  const idxs = [...cumple].sort((a, b) => a - b);
  let mejor = 0;
  let run = 0;
  let prev: number | null = null;
  for (const i of idxs) {
    run = prev !== null && i === prev + 1 ? run + 1 : 1;
    if (run > mejor) mejor = run;
    prev = i;
  }

  // actual: hacia atrás desde la semana actual (o la previa si la actual aún no cumple)
  const actualWk = wk(inicioSemanaActual);
  let cursor = cumple.has(actualWk) ? actualWk : actualWk - 1;
  let actual = 0;
  while (cumple.has(cursor)) {
    actual++;
    cursor--;
  }

  return { actual, mejor };
}
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit`.

- [ ] **Step 5: Commit**

```bash
git add lib/logros.ts lib/logros.test.ts
git commit -m "feat(logros): catálogo + evaluarLogros + calcularRacha (puros)"
```

---

## Task 2: Stats — `getRachaSemanal`, `getLogroMetricas`, `listPRs`

**Files:**
- Modify: `lib/repositories/stats.ts`
- Modify: `lib/repositories/stats.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/stats.ts` (`activo`, `inicioSemana` privada, `estimar1RM`, `getWeeklyVolume` para el patrón de agregación sesión→volumen, `getExercisePRs`) y `lib/repositories/stats.test.ts` (idiom de siembra: workoutSessions/loggedExercises/loggedSets/exercises). `calcularRacha` viene de `@/lib/logros`.

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/stats.test.ts`:

```ts
import { getRachaSemanal, getLogroMetricas, listPRs } from '@/lib/repositories/stats';

// helper local de siembra: una sesión con un ejercicio y una serie
async function sembrarSesion(db, { id, fecha, exerciseId, peso, reps }) {
  await db.workoutSessions.put({ id, userId: null, fecha, updatedAt: 1, deletedAt: null });
  await db.loggedExercises.put({ id: `le-${id}`, sessionId: id, exerciseId, orden: 0, updatedAt: 1, deletedAt: null });
  await db.loggedSets.put({ id: `set-${id}`, loggedExerciseId: `le-${id}`, orden: 0, peso, reps, updatedAt: 1, deletedAt: null });
}

it('getLogroMetricas agrega sesiones, volumen, PRs y mesociclos completados', async () => {
  await Promise.all([db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(), db.exercises.clear(), db.mesocycles.clear()]);
  const NOW = 100 * 86400000;
  await db.exercises.put({ id: 'ex1', userId: null, nombre: 'Press', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });
  await sembrarSesion(db, { id: 's1', fecha: NOW - 2 * 86400000, exerciseId: 'ex1', peso: 100, reps: 5 }); // vol 500
  await sembrarSesion(db, { id: 's2', fecha: NOW - 1 * 86400000, exerciseId: 'ex1', peso: 110, reps: 5 }); // vol 550
  // mesociclo completado (terminó antes de NOW)
  await db.mesocycles.put({ id: 'm1', userId: null, nombre: 'X', objetivo: 'x', semanas: 4, diasPorSemana: 3, notas: null, progresion: [], fechaInicio: NOW - 40 * 86400000, updatedAt: 1, deletedAt: null });
  const m = await getLogroMetricas(3, NOW);
  expect(m.sesionesTotales).toBe(2);
  expect(m.volumenTotal).toBe(1050);
  expect(m.prsTotales).toBe(1); // un ejercicio entrenado
  expect(m.mesociclosCompletados).toBe(1);
});

it('listPRs da el mejor peso por ejercicio con su fecha (empate → más antigua)', async () => {
  await Promise.all([db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(), db.exercises.clear()]);
  await db.exercises.put({ id: 'ex1', userId: null, nombre: 'Press', grupoMuscular: 'pecho', equipamiento: 'barra', tipo: 'compuesto', esPersonalizado: false, updatedAt: 1, deletedAt: null });
  await sembrarSesion(db, { id: 's1', fecha: 1000, exerciseId: 'ex1', peso: 100, reps: 5 });
  await sembrarSesion(db, { id: 's2', fecha: 2000, exerciseId: 'ex1', peso: 100, reps: 5 }); // mismo máx, más reciente
  await sembrarSesion(db, { id: 's3', fecha: 3000, exerciseId: 'ex1', peso: 90, reps: 5 });
  const prs = await listPRs();
  expect(prs).toHaveLength(1);
  expect(prs[0]).toMatchObject({ exerciseId: 'ex1', nombre: 'Press', peso: 100, fecha: 1000 }); // empate → fecha más antigua
});

it('getRachaSemanal cuenta semanas consecutivas cumpliendo el objetivo', async () => {
  await db.workoutSessions.clear();
  const lunes = (n: number) => 4 * 86400000 + n * 7 * 86400000; // un lunes base + n semanas (epoch jueves+ ajustará inicioSemana)
  // 2 sesiones en una semana, 1 en la siguiente, objetivo 2
  await db.workoutSessions.bulkPut([
    { id: 'a', userId: null, fecha: lunes(0), updatedAt: 1, deletedAt: null },
    { id: 'b', userId: null, fecha: lunes(0) + 86400000, updatedAt: 1, deletedAt: null },
  ]);
  const r = await getRachaSemanal(2, lunes(0) + 2 * 86400000);
  expect(r.mejor).toBeGreaterThanOrEqual(1);
  expect(r.actual).toBeGreaterThanOrEqual(1);
});
```
(Tip the `sembrarSesion` helper: type its `db` param loosely as `any`/the Dexie type the test file already imports; match the test file's existing `db` import.)

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement** — in `lib/repositories/stats.ts` add (importing `calcularRacha` from `@/lib/logros`):

```ts
import { calcularRacha } from '@/lib/logros';

const DIA_MS = 86400000;

/** Racha (actual + mejor) de semanas consecutivas cumpliendo el objetivo de sesiones. */
export async function getRachaSemanal(objetivo: number, now: number = Date.now()): Promise<{ actual: number; mejor: number }> {
  const sessions = activo(await db.workoutSessions.toArray());
  const byWeek = new Map<number, number>();
  for (const s of sessions) {
    const w = inicioSemana(s.fecha);
    byWeek.set(w, (byWeek.get(w) ?? 0) + 1);
  }
  const semanas = [...byWeek.entries()].map(([inicioTs, sesiones]) => ({ inicioTs, sesiones }));
  return calcularRacha(semanas, objetivo, inicioSemana(now));
}

export interface PRItem {
  exerciseId: string;
  nombre: string;
  peso: number;
  fecha: number;
}

/** Mejor peso por ejercicio entrenado, con la fecha más antigua en que se alcanzó. */
export async function listPRs(): Promise<PRItem[]> {
  const sessions = activo(await db.workoutSessions.toArray());
  const fechaBySession = new Map(sessions.map((s) => [s.id, s.fecha]));
  const les = activo(await db.loggedExercises.toArray());
  const leInfo = new Map(les.map((le) => [le.id, { exerciseId: le.exerciseId, fecha: fechaBySession.get(le.sessionId) ?? 0 }]));
  const sets = activo(await db.loggedSets.toArray());

  // 1ª pasada: máx peso por ejercicio. 2ª: fecha más antigua que alcanza ese máx.
  const maxPeso = new Map<string, number>();
  for (const set of sets) {
    const info = leInfo.get(set.loggedExerciseId);
    if (!info) continue;
    maxPeso.set(info.exerciseId, Math.max(maxPeso.get(info.exerciseId) ?? 0, set.peso));
  }
  const fechaPR = new Map<string, number>();
  for (const set of sets) {
    const info = leInfo.get(set.loggedExerciseId);
    if (!info) continue;
    if (set.peso === maxPeso.get(info.exerciseId)) {
      const prev = fechaPR.get(info.exerciseId);
      if (prev === undefined || info.fecha < prev) fechaPR.set(info.exerciseId, info.fecha);
    }
  }
  const ids = [...maxPeso.keys()];
  const exs = await db.exercises.bulkGet(ids);
  return ids
    .map((id, i) => ({ exerciseId: id, nombre: exs[i]?.nombre ?? '—', peso: maxPeso.get(id)!, fecha: fechaPR.get(id) ?? 0 }))
    .sort((a, b) => b.peso - a.peso);
}

/** Métricas agregadas para evaluar logros. `objetivo` = objetivo semanal (para la mejor racha). */
export async function getLogroMetricas(objetivo: number, now: number = Date.now()): Promise<import('@/lib/logros').LogroMetricas> {
  const sessions = activo(await db.workoutSessions.toArray());
  const sets = activo(await db.loggedSets.toArray());
  const les = activo(await db.loggedExercises.toArray());
  const exById = new Map(les.map((le) => [le.id, le.exerciseId]));

  const volumenTotal = sets.reduce((acc, s) => acc + s.peso * s.reps, 0);
  const exConSets = new Set<string>();
  for (const s of sets) {
    const ex = exById.get(s.loggedExerciseId);
    if (ex) exConSets.add(ex);
  }
  const mesos = activo(await db.mesocycles.toArray());
  const mesociclosCompletados = mesos.filter((m) => m.fechaInicio + m.semanas * 7 * DIA_MS < now).length;
  const { mejor } = await getRachaSemanal(objetivo, now);

  return {
    sesionesTotales: sessions.length,
    volumenTotal,
    prsTotales: exConSets.size,
    mejorRacha: mejor,
    mesociclosCompletados,
  };
}
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` + `npx eslint lib/repositories/stats.ts`.

- [ ] **Step 5: Commit**

```bash
git add lib/repositories/stats.ts lib/repositories/stats.test.ts
git commit -m "feat(logros): stats getRachaSemanal/getLogroMetricas/listPRs"
```

---

## Task 3: `reconciliarLogros`

**Files:**
- Create: `lib/reconciliar-logros.ts`
- Create: `lib/reconciliar-logros.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/logros.ts` (`evaluarLogros`), `lib/repositories/stats.ts` (`getLogroMetricas`), `lib/repositories/achievements.ts` (`listAchievements`/`unlockAchievement`).

- [ ] **Step 1: Write the failing test** — create `lib/reconciliar-logros.test.ts`:

```ts
import { describe, it, expect, beforeEach } from 'vitest';
import { db } from '@/lib/db/database';
import { reconciliarLogros } from '@/lib/reconciliar-logros';
import { listAchievements } from '@/lib/repositories/achievements';

async function sembrarSesiones(n: number) {
  for (let i = 0; i < n; i++) {
    await db.workoutSessions.put({ id: `s${i}`, userId: null, fecha: 1000 + i, updatedAt: 1, deletedAt: null });
  }
}

beforeEach(async () => {
  await Promise.all([db.workoutSessions.clear(), db.loggedExercises.clear(), db.loggedSets.clear(), db.mesocycles.clear(), db.achievements.clear()]);
});

describe('reconciliarLogros', () => {
  it('desbloquea los hitos cumplidos y es idempotente', async () => {
    await sembrarSesiones(10); // cumple sesiones-10
    const nuevas = await reconciliarLogros(3, 99999999);
    expect(nuevas).toContain('sesiones-10');
    expect((await listAchievements()).map((a) => a.clave)).toContain('sesiones-10');

    // segunda llamada: no añade nada nuevo
    const otra = await reconciliarLogros(3, 99999999);
    expect(otra).toEqual([]);
    expect((await listAchievements()).filter((a) => a.clave === 'sesiones-10')).toHaveLength(1);
  });

  it('sin datos no desbloquea nada', async () => {
    expect(await reconciliarLogros(3, 99999999)).toEqual([]);
    expect(await listAchievements()).toHaveLength(0);
  });
});
```

- [ ] **Step 2: Run → FAIL**.

- [ ] **Step 3: Implement** — create `lib/reconciliar-logros.ts`:

```ts
import { evaluarLogros } from '@/lib/logros';
import { getLogroMetricas } from '@/lib/repositories/stats';
import { listAchievements, unlockAchievement } from '@/lib/repositories/achievements';

/**
 * Desbloquea (persistente) los hitos que se cumplen ahora y aún no estaban registrados.
 * Idempotente. Devuelve las claves recién desbloqueadas. `objetivo` = objetivo semanal.
 */
export async function reconciliarLogros(objetivo: number, now: number = Date.now()): Promise<string[]> {
  const metricas = await getLogroMetricas(objetivo, now);
  const cumplidos = evaluarLogros(metricas);
  const yaDesbloqueados = new Set((await listAchievements()).map((a) => a.clave));
  const nuevas = cumplidos.filter((c) => !yaDesbloqueados.has(c));
  for (const clave of nuevas) await unlockAchievement(clave);
  return nuevas;
}
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` + `npx eslint lib/reconciliar-logros.ts`.

- [ ] **Step 5: Commit**

```bash
git add lib/reconciliar-logros.ts lib/reconciliar-logros.test.ts
git commit -m "feat(logros): reconciliarLogros (desbloqueo persistente idempotente)"
```

---

## Task 4: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde.

---

## Self-Review (hecho)

- **Spec cobertura (F2):** `LOGROS_DEF` (sesiones/volumen/racha/mesociclo) + `evaluarLogros` puro ✓; `calcularRacha` puro (actual cuenta atrás, semana en curso no rompe, mejor histórica, hueco rompe) ✓; `getRachaSemanal`/`getLogroMetricas`/`listPRs` derivados (vol total, nº ejercicios=PRs, mesociclos completados por fecha, mejor peso por ejercicio con fecha más antigua) ✓; `reconciliarLogros` idempotente que persiste con `unlockAchievement` ✓.
- **Tipos consistentes:** `LogroMetricas`/`LogroDef`/`PRItem` compartidos; `getLogroMetricas` toma `objetivo` (necesario para mejorRacha) — desviación menor vs spec, justificada.
- **Casos límite:** sin datos → 0/0/[]; empate de peso → fecha más antigua; semana en curso por debajo no rompe; reconciliar idempotente.
- **Sin placeholders:** todo el código presente. La UI (F3) consume `getRachaSemanal`/`getAchievementMap`/`listPRs`/`reconciliarLogros`.
