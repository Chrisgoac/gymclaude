# Cuerpo D1c — Pantalla `/cuerpo` (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pantalla `/cuerpo` para registrar peso/medidas, gestionar métricas personalizadas, y ver por cada métrica una gráfica de tendencia + valor actual + cambio + lista de entradas con borrado. Más una tarjeta de acceso desde Progreso.

**Architecture:** Componentes de presentación dirigidos por props (testeables sin Dexie) + una página cliente que hace el `useLiveQuery` y reparte datos. `resumenSerie` (puro) calcula actual/delta/puntos. La gráfica reusa el patrón de `components/exercise-chart.tsx` (Recharts, `currentColor`, tooltip brutalist). El formulario usa `addMetric` (D1a) y `addMetricaPersonalizada` (D1b); las personalizadas se leen reactivas con `useSetting`.

**Tech Stack:** Next.js 16 App Router (client) · Recharts ^3.8.1 · Tailwind "Brutalist Iron" · Dexie `useLiveQuery` · vitest (proyectos `app` jsdom + `api` node).

**Nota:** Fase **D1c** del spec `docs/superpowers/specs/2026-06-09-seguimiento-corporal-design.md`. D1a (entidad `BodyMetric` + repo `lib/repositories/body.ts`) y D1b (`lib/body-metrics.ts`: `METRICAS_PREDEF`, `ORDEN_PREDEF`, `resolverMetrica`, `addMetricaPersonalizada`, `listMetricasPersonalizadas`, `CLAVE_PERSONALIZADAS`, tipos `MetricaDef`/`MetricaPersonalizada`) ya están mergeadas.

**Repo D1a disponible** (`lib/repositories/body.ts`): `listMetrics(tipo)`, `listTipos()`, `addMetric(tipo, valor, fecha?)`, `deleteMetric(id)`. Entidad `BodyMetric { id, userId, tipo, valor, fecha, updatedAt, deletedAt }`.

---

## File Structure

- **Create** `lib/body-stats.ts` — `resumenSerie(metrics)` puro (actual, delta vs primero, puntos para gráfica).
- **Create** `lib/body-stats.test.ts`.
- **Create** `components/body-metric-chart.tsx` — gráfica de línea de una serie (mirror de exercise-chart).
- **Create** `components/body-metric-card.tsx` — tarjeta de una métrica (header + actual/delta + gráfica + lista con borrado). Presentacional (props).
- **Create** `components/body-metric-card.test.tsx`.
- **Create** `components/body-form.tsx` — registrar entrada + gestionar personalizadas.
- **Create** `components/body-form.test.tsx`.
- **Modify** `lib/repositories/body.ts` — añade `listAllMetrics()`.
- **Modify** `lib/repositories/body.test.ts` — test de `listAllMetrics`.
- **Create** `app/cuerpo/page.tsx` — página cliente que compone todo.
- **Modify** `app/progreso/page.tsx` — tarjeta "Seguimiento corporal →".

---

## Task 1: `resumenSerie` (puro)

**Files:**
- Create: `lib/body-stats.ts`
- Create: `lib/body-stats.test.ts`

- [ ] **Step 0: READ FIRST**

Read `lib/db/types.ts` (interface `BodyMetric`). `resumenSerie` recibe entradas YA activas y ordenadas asc por fecha (como las devuelve `listMetrics`). Calcula: `actual` (valor de la última), `primero` (valor de la primera), `delta = actual - primero` (null si <2 entradas), y `puntos` = `[{ fecha: 'dd/mm', valor }]` para la gráfica. Sin datos → `{ actual: null, delta: null, puntos: [] }`.

- [ ] **Step 1: Write the failing test** — create `lib/body-stats.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { resumenSerie } from '@/lib/body-stats';
import type { BodyMetric } from '@/lib/db/types';

const bm = (valor: number, fecha: number): BodyMetric => ({
  id: `${fecha}`, userId: null, tipo: 'peso', valor, fecha, updatedAt: fecha, deletedAt: null,
});

describe('resumenSerie', () => {
  it('sin datos → todo vacío', () => {
    expect(resumenSerie([])).toEqual({ actual: null, primero: null, delta: null, puntos: [] });
  });
  it('una sola entrada → delta null', () => {
    const r = resumenSerie([bm(80, 1000)]);
    expect(r.actual).toBe(80);
    expect(r.primero).toBe(80);
    expect(r.delta).toBeNull();
    expect(r.puntos).toHaveLength(1);
  });
  it('varias entradas → actual=última, delta vs primera', () => {
    const r = resumenSerie([bm(80, 1000), bm(78.5, 2000), bm(77, 3000)]);
    expect(r.actual).toBe(77);
    expect(r.primero).toBe(80);
    expect(r.delta).toBe(-3);
    expect(r.puntos.map((p) => p.valor)).toEqual([80, 78.5, 77]);
  });
});
```

- [ ] **Step 2: Run → FAIL**

Run: `npx vitest run lib/body-stats.test.ts` → FAIL (module not found).

- [ ] **Step 3: Implement** — create `lib/body-stats.ts`:

```ts
import type { BodyMetric } from '@/lib/db/types';

export interface PuntoSerie {
  fecha: string;
  valor: number;
}

export interface ResumenSerie {
  actual: number | null;
  primero: number | null;
  delta: number | null;
  puntos: PuntoSerie[];
}

/**
 * Resume una serie de entradas (activas, orden cronológico asc) de una métrica:
 * valor actual (última), primero, delta = actual - primero (null si <2), y puntos para la gráfica.
 */
export function resumenSerie(metrics: BodyMetric[]): ResumenSerie {
  if (metrics.length === 0) return { actual: null, primero: null, delta: null, puntos: [] };
  const primero = metrics[0].valor;
  const actual = metrics[metrics.length - 1].valor;
  const delta = metrics.length >= 2 ? actual - primero : null;
  const puntos = metrics.map((m) => ({
    fecha: new Date(m.fecha).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit' }),
    valor: m.valor,
  }));
  return { actual, primero, delta, puntos };
}
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` clean.

- [ ] **Step 5: Commit**

```bash
git add lib/body-stats.ts lib/body-stats.test.ts
git commit -m "feat(cuerpo): resumenSerie (actual/delta/puntos, puro)"
```

---

## Task 2: Gráfica de una serie

**Files:**
- Create: `components/body-metric-chart.tsx`

> Mirror de `components/exercise-chart.tsx`. Sin test unitario (Recharts/ResponsiveContainer no renderiza en jsdom sin dimensiones; igual que exercise-chart, se verifica por build). Recibe `puntos` ya formateados.

- [ ] **Step 0: READ FIRST**

Read `components/exercise-chart.tsx` (patrón exacto: `'use client'`, ResponsiveContainer h-56, XAxis/YAxis con `axisLine={{ stroke: 'currentColor' }}`, Tooltip brutalist, Line `type="stepAfter"`... para peso usamos `type="monotone"` que es más natural para una curva continua).

- [ ] **Step 1: Implement** — create `components/body-metric-chart.tsx`:

```tsx
'use client';

import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { PuntoSerie } from '@/lib/body-stats';

export function BodyMetricChart({ puntos }: { puntos: PuntoSerie[] }) {
  return (
    <div className="h-48 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={puntos} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <XAxis dataKey="fecha" fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} />
          <YAxis fontSize={11} tickLine={false} axisLine={{ stroke: 'currentColor' }} domain={['auto', 'auto']} />
          <Tooltip
            cursor={{ stroke: 'currentColor', strokeWidth: 1 }}
            contentStyle={{ border: '2px solid currentColor', borderRadius: 0, fontFamily: 'var(--font-mono)', fontSize: 12 }}
          />
          <Line
            type="monotone"
            dataKey="valor"
            stroke="currentColor"
            className="text-primary"
            strokeWidth={3}
            dot={{ r: 3, strokeWidth: 0, fill: 'currentColor' }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
```

- [ ] **Step 2: Typecheck** — `npx tsc --noEmit` clean. (Render real se valida en el build de Task 6.)

- [ ] **Step 3: Commit**

```bash
git add components/body-metric-chart.tsx
git commit -m "feat(cuerpo): gráfica de tendencia de una métrica"
```

---

## Task 3: Tarjeta de una métrica (presentacional)

**Files:**
- Create: `components/body-metric-card.tsx`
- Create: `components/body-metric-card.test.tsx`

> Componente presentacional dirigido por props: recibe `tipo`, `metrics` (activas, asc), y `def` ({label, unidad} ya resuelto). Renderiza header (label + unidad), valor actual + delta, gráfica, y lista de entradas con botón borrar (llama `deleteMetric`). La página le pasa los datos (no hace `useLiveQuery` aquí → test determinista).

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/body.ts` (`deleteMetric(id)`), `components/coach-chat.test.tsx` (idiom de mock de repos con vi.mock + render/userEvent), y `lib/body-stats.ts` (`resumenSerie`). La lista de entradas se muestra más reciente primero (invertir `metrics`). El borrado es un botón `✕` con `aria-label`.

- [ ] **Step 1: Write the failing test** — create `components/body-metric-card.test.tsx`:

```tsx
import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { BodyMetric } from '@/lib/db/types';

const deleteMetric = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/repositories/body', () => ({ deleteMetric: (...a: unknown[]) => deleteMetric(...a) }));
// La gráfica usa Recharts (no renderiza en jsdom): la stubeamos.
vi.mock('@/components/body-metric-chart', () => ({ BodyMetricChart: () => <div data-testid="chart" /> }));

import { BodyMetricCard } from '@/components/body-metric-card';

const bm = (id: string, valor: number, fecha: number): BodyMetric => ({
  id, userId: null, tipo: 'peso', valor, fecha, updatedAt: fecha, deletedAt: null,
});

beforeEach(() => { deleteMetric.mockClear(); });

it('muestra label, valor actual y delta', () => {
  render(<BodyMetricCard tipo="peso" def={{ label: 'Peso', unidad: 'kg' }} metrics={[bm('a', 80, 1000), bm('b', 77, 2000)]} />);
  expect(screen.getByText('Peso')).toBeInTheDocument();
  expect(screen.getByText(/77/)).toBeInTheDocument();
  expect(screen.getByText(/-3/)).toBeInTheDocument(); // delta
});

it('el botón borrar llama a deleteMetric con el id', async () => {
  render(<BodyMetricCard tipo="peso" def={{ label: 'Peso', unidad: 'kg' }} metrics={[bm('a', 80, 1000)]} />);
  await userEvent.click(screen.getByRole('button', { name: /eliminar/i }));
  expect(deleteMetric).toHaveBeenCalledWith('a');
});
```

- [ ] **Step 2: Run → FAIL**

Run: `npx vitest run components/body-metric-card.test.tsx` → FAIL (module not found).

- [ ] **Step 3: Implement** — create `components/body-metric-card.tsx`:

```tsx
'use client';

import type { BodyMetric } from '@/lib/db/types';
import type { MetricaDef } from '@/lib/body-metrics';
import { resumenSerie } from '@/lib/body-stats';
import { deleteMetric } from '@/lib/repositories/body';
import { BodyMetricChart } from '@/components/body-metric-chart';

export function BodyMetricCard({
  tipo,
  def,
  metrics,
}: {
  tipo: string;
  def: MetricaDef;
  metrics: BodyMetric[];
}) {
  const { actual, delta, puntos } = resumenSerie(metrics);
  const recientes = [...metrics].reverse();
  const signo = delta != null && delta > 0 ? '+' : '';

  return (
    <section className="brutal-box space-y-3 p-3" aria-label={def.label}>
      <header className="flex items-baseline justify-between">
        <h3 className="text-lg font-bold">{def.label}</h3>
        <div className="text-right">
          <span className="font-[family-name:var(--font-display)] text-2xl tabular-nums">
            {actual ?? '—'}
          </span>
          <span className="label-mono ml-1 text-[10px] text-muted-foreground">{def.unidad}</span>
          {delta != null && (
            <span
              className={`label-mono ml-2 text-[10px] ${delta < 0 ? 'text-primary' : 'text-muted-foreground'}`}
            >
              {signo}
              {delta.toFixed(1)} {def.unidad}
            </span>
          )}
        </div>
      </header>

      {puntos.length >= 2 && <BodyMetricChart puntos={puntos} />}

      <ul className="divide-y-2 divide-foreground border-t-2 border-foreground">
        {recientes.map((m) => (
          <li key={m.id} className="flex items-center justify-between gap-2 py-1.5">
            <span className="tabular-nums">
              {m.valor} {def.unidad}
            </span>
            <span className="flex items-center gap-3">
              <span className="label-mono text-[10px] text-muted-foreground">
                {new Date(m.fecha).toLocaleDateString('es-ES', { day: '2-digit', month: '2-digit', year: '2-digit' })}
              </span>
              <button
                type="button"
                className="grid size-7 place-items-center text-muted-foreground hover:text-destructive"
                aria-label={`Eliminar entrada ${def.label}`}
                onClick={() => void deleteMetric(m.id)}
              >
                ✕
              </button>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` clean + `npx eslint components/body-metric-card.tsx`.

- [ ] **Step 5: Commit**

```bash
git add components/body-metric-card.tsx components/body-metric-card.test.tsx
git commit -m "feat(cuerpo): tarjeta de métrica (actual/delta/gráfica/lista)"
```

---

## Task 4: Formulario (registrar + gestionar personalizadas)

**Files:**
- Create: `components/body-form.tsx`
- Create: `components/body-form.test.tsx`

> Cliente. Selector de métrica (predefinidas en orden + personalizadas), input de valor (decimal, admite coma), input de fecha (`type="date"`, hoy por defecto) → `addMetric`. Sección plegable "Gestionar métricas": nombre + unidad → `addMetricaPersonalizada`. Personalizadas leídas reactivas con `useSetting`.

- [ ] **Step 0: READ FIRST**

Read `lib/num.ts` (`parseDecimal` NO admite coma → hay que `replace(',', '.')` antes), `lib/body-metrics.ts` (`ORDEN_PREDEF`, `METRICAS_PREDEF`, `CLAVE_PERSONALIZADAS`, `addMetricaPersonalizada`, tipos), `lib/use-setting.ts` (`useSetting<T>(clave, fallback)`), `lib/repositories/body.ts` (`addMetric(tipo, valor, fecha?)`), `components/coach-chat.test.tsx` (idiom de mocks). Componentes UI: `@/components/ui/input`, `@/components/ui/label`, `@/components/ui/button`.

Fecha: `<input type="date">` da `'YYYY-MM-DD'`. Conversión a epoch ms local: `new Date(`${valorFecha}T00:00:00`).getTime()`. Hoy por defecto: helper `hoyISO()` = `new Date()` → `toISOString().slice(0,10)` **NO** (UTC); usa partes locales:
```ts
function hoyISO(): string {
  const d = new Date();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}
```

- [ ] **Step 1: Write the failing test** — create `components/body-form.test.tsx`:

```tsx
import { it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const addMetric = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/repositories/body', () => ({ addMetric: (...a: unknown[]) => addMetric(...a) }));

const addMetricaPersonalizada = vi.fn().mockResolvedValue({ clave: 'grasa', label: '% Grasa', unidad: '%' });
const useSettingMock = vi.fn();
vi.mock('@/lib/body-metrics', async (orig) => {
  const real = await orig<typeof import('@/lib/body-metrics')>();
  return { ...real, addMetricaPersonalizada: (...a: unknown[]) => addMetricaPersonalizada(...a) };
});
vi.mock('@/lib/use-setting', () => ({ useSetting: (...a: unknown[]) => useSettingMock(...a) }));

import { BodyForm } from '@/components/body-form';

beforeEach(() => {
  addMetric.mockClear();
  addMetricaPersonalizada.mockClear();
  useSettingMock.mockReturnValue([[], vi.fn()]); // sin personalizadas por defecto
});

it('registrar una entrada llama a addMetric con tipo y valor', async () => {
  render(<BodyForm />);
  await userEvent.selectOptions(screen.getByLabelText(/métrica/i), 'cintura');
  await userEvent.type(screen.getByLabelText(/valor/i), '84,5');
  await userEvent.click(screen.getByRole('button', { name: /registrar/i }));
  expect(addMetric).toHaveBeenCalledTimes(1);
  const [tipo, valor] = addMetric.mock.calls[0];
  expect(tipo).toBe('cintura');
  expect(valor).toBe(84.5); // coma convertida a punto
});

it('las personalizadas aparecen en el selector', () => {
  useSettingMock.mockReturnValue([[{ clave: 'grasa', label: '% Grasa', unidad: '%' }], vi.fn()]);
  render(<BodyForm />);
  expect(screen.getByRole('option', { name: /% Grasa/ })).toBeInTheDocument();
});

it('gestionar métricas crea una personalizada', async () => {
  render(<BodyForm />);
  await userEvent.click(screen.getByRole('button', { name: /gestionar métricas/i }));
  await userEvent.type(screen.getByLabelText(/nombre/i), 'Grasa');
  await userEvent.type(screen.getByLabelText(/unidad/i), '%');
  await userEvent.click(screen.getByRole('button', { name: /añadir métrica/i }));
  expect(addMetricaPersonalizada).toHaveBeenCalledWith('Grasa', '%');
});
```

- [ ] **Step 2: Run → FAIL**

Run: `npx vitest run components/body-form.test.tsx` → FAIL (module not found).

- [ ] **Step 3: Implement** — create `components/body-form.tsx`:

```tsx
'use client';

import { useState } from 'react';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { parseDecimal } from '@/lib/num';
import { addMetric } from '@/lib/repositories/body';
import {
  ORDEN_PREDEF,
  METRICAS_PREDEF,
  CLAVE_PERSONALIZADAS,
  addMetricaPersonalizada,
  type MetricaPersonalizada,
} from '@/lib/body-metrics';
import { useSetting } from '@/lib/use-setting';

function hoyISO(): string {
  const d = new Date();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

export function BodyForm() {
  const [personalizadas] = useSetting<MetricaPersonalizada[]>(CLAVE_PERSONALIZADAS, []);
  const [tipo, setTipo] = useState('peso');
  const [valor, setValor] = useState('');
  const [fecha, setFecha] = useState(hoyISO());
  const [error, setError] = useState('');
  const [gestion, setGestion] = useState(false);
  const [nombre, setNombre] = useState('');
  const [unidad, setUnidad] = useState('');
  const [gestionError, setGestionError] = useState('');

  async function registrar(e: React.FormEvent) {
    e.preventDefault();
    const num = parseDecimal(valor.replace(',', '.'));
    if (num <= 0) {
      setError('Introduce un valor mayor que 0');
      return;
    }
    const fechaMs = new Date(`${fecha}T00:00:00`).getTime();
    await addMetric(tipo, num, fechaMs);
    setValor('');
    setError('');
  }

  async function crearPersonalizada(e: React.FormEvent) {
    e.preventDefault();
    try {
      await addMetricaPersonalizada(nombre, unidad);
      setNombre('');
      setUnidad('');
      setGestionError('');
    } catch (err) {
      setGestionError(err instanceof Error ? err.message : 'No se pudo crear');
    }
  }

  return (
    <div className="space-y-4">
      <form onSubmit={registrar} className="brutal-box space-y-3 p-3">
        <div className="space-y-1">
          <Label htmlFor="metrica">Métrica</Label>
          <select
            id="metrica"
            className="w-full border-2 border-foreground bg-card p-2"
            value={tipo}
            onChange={(e) => setTipo(e.target.value)}
          >
            {ORDEN_PREDEF.map((k) => (
              <option key={k} value={k}>
                {METRICAS_PREDEF[k].label} ({METRICAS_PREDEF[k].unidad})
              </option>
            ))}
            {personalizadas.map((m) => (
              <option key={m.clave} value={m.clave}>
                {m.label} ({m.unidad})
              </option>
            ))}
          </select>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-1">
            <Label htmlFor="valor">Valor</Label>
            <Input
              id="valor"
              inputMode="decimal"
              value={valor}
              onChange={(e) => setValor(e.target.value)}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="fecha">Fecha</Label>
            <input
              id="fecha"
              type="date"
              className="h-9 w-full border-2 border-foreground bg-card px-2"
              value={fecha}
              onChange={(e) => setFecha(e.target.value)}
            />
          </div>
        </div>

        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button type="submit" className="w-full">
          Registrar
        </Button>
      </form>

      <button
        type="button"
        className="label-mono text-[11px] text-muted-foreground underline"
        onClick={() => setGestion((g) => !g)}
      >
        Gestionar métricas
      </button>

      {gestion && (
        <form onSubmit={crearPersonalizada} className="brutal-box space-y-3 p-3">
          <p className="label-mono text-[10px] text-muted-foreground">Nueva métrica personalizada</p>
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <Label htmlFor="nombre">Nombre</Label>
              <Input id="nombre" value={nombre} onChange={(e) => setNombre(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="unidad">Unidad</Label>
              <Input id="unidad" value={unidad} onChange={(e) => setUnidad(e.target.value)} />
            </div>
          </div>
          {gestionError && <p className="text-sm text-destructive">{gestionError}</p>}
          <Button type="submit" variant="outline" className="w-full">
            Añadir métrica
          </Button>
        </form>
      )}
    </div>
  );
}
```

- [ ] **Step 4: Run → PASS** + `npx tsc --noEmit` + `npx eslint components/body-form.tsx`.

If the `vi.mock('@/lib/body-metrics', …)` partial-mock factory shape differs in this vitest version, adapt to the working idiom (e.g. `vi.importActual`) but keep `addMetricaPersonalizada` mocked and the rest real.

- [ ] **Step 5: Commit**

```bash
git add components/body-form.tsx components/body-form.test.tsx
git commit -m "feat(cuerpo): formulario de registro + gestión de personalizadas"
```

---

## Task 5: Repo `listAllMetrics` + página `/cuerpo` + tarjeta en Progreso

**Files:**
- Modify: `lib/repositories/body.ts`
- Modify: `lib/repositories/body.test.ts`
- Create: `app/cuerpo/page.tsx`
- Modify: `app/progreso/page.tsx`

- [ ] **Step 0: READ FIRST**

Read `lib/repositories/body.ts` (`activo` helper, `now`), `lib/repositories/body.test.ts` (idiom), `app/coach/page.tsx` (estructura de página cliente), `app/progreso/page.tsx` (dónde insertar la tarjeta + idiom de `Link` brutal-box; ver `app/page.tsx` para el idiom exacto de tarjeta-enlace).

- [ ] **Step 1: Write the failing test** — append to `lib/repositories/body.test.ts`:

```ts
it('listAllMetrics devuelve todas las activas ordenadas por fecha asc', async () => {
  await db.bodyMetrics.clear();
  await addMetric('peso', 80, 2000);
  await addMetric('cintura', 84, 1000);
  const todas = await listAllMetrics();
  expect(todas.map((m) => m.fecha)).toEqual([1000, 2000]);
  expect(todas).toHaveLength(2);
});
```
(Add `listAllMetrics` to the existing import from `@/lib/repositories/body` at the top of the test file.)

- [ ] **Step 2: Run → FAIL** (`listAllMetrics` not exported).

- [ ] **Step 3: Implement** — in `lib/repositories/body.ts` add:

```ts
/** Todas las entradas activas (de todos los tipos), orden cronológico asc. */
export async function listAllMetrics(): Promise<BodyMetric[]> {
  const all = activo(await db.bodyMetrics.toArray());
  return all.sort((a, b) => a.fecha - b.fecha);
}
```

- [ ] **Step 4: Run → PASS**.

- [ ] **Step 5: Create the page** — create `app/cuerpo/page.tsx`:

```tsx
'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { listAllMetrics } from '@/lib/repositories/body';
import { useSetting } from '@/lib/use-setting';
import {
  resolverMetrica,
  CLAVE_PERSONALIZADAS,
  ORDEN_PREDEF,
  type MetricaPersonalizada,
} from '@/lib/body-metrics';
import type { BodyMetric } from '@/lib/db/types';
import { BodyForm } from '@/components/body-form';
import { BodyMetricCard } from '@/components/body-metric-card';

export default function CuerpoPage() {
  const metrics = useLiveQuery(() => listAllMetrics(), []);
  const [personalizadas] = useSetting<MetricaPersonalizada[]>(CLAVE_PERSONALIZADAS, []);

  // Agrupa por tipo conservando orden cronológico asc dentro de cada grupo.
  const porTipo = new Map<string, BodyMetric[]>();
  for (const m of metrics ?? []) {
    const arr = porTipo.get(m.tipo) ?? [];
    arr.push(m);
    porTipo.set(m.tipo, arr);
  }

  // Orden de tarjetas: predefinidas primero (orden del catálogo), luego el resto por recencia.
  const tipos = [...porTipo.keys()].sort((a, b) => {
    const ia = ORDEN_PREDEF.indexOf(a);
    const ib = ORDEN_PREDEF.indexOf(b);
    if (ia !== -1 || ib !== -1) return (ia === -1 ? 999 : ia) - (ib === -1 ? 999 : ib);
    return a.localeCompare(b);
  });

  return (
    <div className="space-y-6">
      <div>
        <p className="label-mono text-[11px] text-muted-foreground">Seguimiento</p>
        <h1 className="text-5xl">Cuerpo</h1>
      </div>

      <BodyForm />

      {metrics === undefined ? (
        <p className="text-muted-foreground">Cargando…</p>
      ) : tipos.length === 0 ? (
        <p className="text-muted-foreground">
          Aún no has registrado ninguna medida. Empieza con tu peso arriba.
        </p>
      ) : (
        <div className="space-y-4">
          {tipos.map((tipo) => (
            <BodyMetricCard
              key={tipo}
              tipo={tipo}
              def={resolverMetrica(tipo, personalizadas)}
              metrics={porTipo.get(tipo)!}
            />
          ))}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 6: Add the Progreso card** — in `app/progreso/page.tsx`:

1. Ensure imports: `import Link from 'next/link';` and a Lucide icon (`import { Scale } from 'lucide-react';` — confirm `Scale` exists in `lucide-react`; if not, use `Ruler`).
2. Add this card near the top of the page (after the `<h1>Progreso</h1>`, before the first data section), adapting to the real structure:

```tsx
      <Link
        href="/cuerpo"
        className="brutal-box flex items-center justify-between gap-3 px-3 py-2.5 transition-transform active:translate-x-[2px] active:translate-y-[2px]"
      >
        <span className="flex items-center gap-2">
          <Scale className="size-4 text-primary" strokeWidth={2} aria-hidden="true" />
          <span className="font-semibold">Seguimiento corporal</span>
        </span>
        <span className="label-mono text-[10px] text-muted-foreground">→</span>
      </Link>
```

- [ ] **Step 7: Typecheck + lint + build**

Run: `npx tsc --noEmit && npx eslint app/cuerpo/page.tsx app/progreso/page.tsx lib/repositories/body.ts && npm run build`
Expected: clean; `/cuerpo` aparece en el output del build.

- [ ] **Step 8: Commit**

```bash
git add lib/repositories/body.ts lib/repositories/body.test.ts app/cuerpo/page.tsx app/progreso/page.tsx
git commit -m "feat(cuerpo): pantalla /cuerpo + acceso desde Progreso"
```

---

## Task 6: Verificación final

- [ ] **Step 1:** `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde; ruta `/cuerpo` presente en el build.

---

## Self-Review (hecho)

- **Spec cobertura (sección "Pantalla `/cuerpo`"):** registrar (selector predef+personalizadas, valor decimal con coma, fecha hoy editable) → `addMetric` ✓; "Gestionar métricas" crea personalizada ✓; por métrica con datos (`listTipos`/grupos): gráfica de tendencia + valor actual + cambio vs primero + lista con borrado (tombstone) ✓; estética Brutalist + unidades vía `resolverMetrica` ✓; tarjeta "Seguimiento corporal →" en Progreso ✓.
- **Casos límite:** sin datos → mensaje invita a registrar ✓; una sola entrada → `delta=null`, sin gráfica (puntos<2) ✓; personalizada borrada de ajustes pero con entradas → `resolverMetrica` fallback `{label: tipo, unidad: ''}` ✓; valores ≤0 rechazados, decimales con coma ✓.
- **Tipos consistentes:** `MetricaDef`/`MetricaPersonalizada`/`PuntoSerie`/`ResumenSerie` reutilizados; `resumenSerie` recibe orden asc (como `listMetrics`/`listAllMetrics` garantizan); `BodyMetricCard` presentacional (datos por props) → tests deterministas sin Dexie.
- **Sin placeholders:** todo el código presente. Gráfica sin test unitario por límite jsdom/Recharts (igual que `exercise-chart`), validada en build; card/form stubean la gráfica.
