# Coach B3 — Ruta /api/coach + DeepSeek (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Una ruta `POST /api/coach` que, autenticada con Clerk, llama a DeepSeek (API key directa) con el AI SDK pasándole el system prompt (persona de coach + snapshot + disclaimer) y devuelve la respuesta en streaming.

**Architecture:** AI SDK v6 (`ai` + `@ai-sdk/deepseek`). Un módulo puro `lib/coach-prompt.ts` arma el system prompt a partir del `CoachSnapshot` (B2). Un módulo fino `lib/coach-model.ts` expone el modelo + `deepseekConfigured()`. La ruta hace `auth()` (401), comprueba la key (503), y `streamText(...).toUIMessageStreamResponse()`. El hook de cliente `useChat` se cablea en B4.

**Tech Stack:** Next.js 16 App Router · AI SDK v6 (`ai`, `@ai-sdk/deepseek`) · Clerk · Vitest (proyecto `api` en node).

**Nota crítica (AGENTS.md + skill vercel:ai-sdk):** "Todo lo que sabes del AI SDK está desactualizado." Tras instalar `ai`, el implementador DEBE verificar la API contra `node_modules/ai/docs/` y `node_modules/@ai-sdk/deepseek/docs/`. La API vigente confirmada (docs ai-sdk.dev, 2026-06): ruta = `streamText({ model, system, messages: convertToModelMessages(messages) }).toUIMessageStreamResponse()` (imports de `ai`); provider = `import { deepseek } from '@ai-sdk/deepseek'` (lee `DEEPSEEK_API_KEY` del entorno), modelo `deepseek('deepseek-chat')`. Si la versión instalada difiere, AJUSTAR según los docs locales y reportarlo.

Fase **B3** del spec `docs/superpowers/specs/2026-06-09-coach-ia-design.md` (sección "Ruta POST /api/coach"). B1 (entidad) y B2 (snapshot, `lib/coach-snapshot.ts` exporta `CoachSnapshot`) ya están en main. B4 (UI) y B5 (accesos) después.

---

## File Structure

- **Modify** `package.json` — deps `ai` + `@ai-sdk/deepseek`.
- **Create** `lib/coach-prompt.ts` — `DISCLAIMER`, `systemPrompt(snapshot): string` (puro).
- **Create** `lib/coach-prompt.test.ts`.
- **Create** `lib/coach-model.ts` — `MODELO_COACH`, `deepseekConfigured()`.
- **Create** `app/api/coach/route.ts` — handler POST.
- **Create** `app/api/coach/route.test.ts`.

---

## Task 1: Instalar el AI SDK + provider DeepSeek

**Files:**
- Modify: `package.json`

- [ ] **Step 1: Install deps**

Run: `npm install ai @ai-sdk/deepseek`
(Usa el gestor del repo — hay `package-lock.json`, así que `npm install`.)

- [ ] **Step 2: Verify the API exists in the installed package**

Run: `ls node_modules/ai/docs/ 2>/dev/null; grep -rl "convertToModelMessages\|toUIMessageStreamResponse" node_modules/ai/dist 2>/dev/null | head; ls node_modules/@ai-sdk/deepseek`
Expected: el paquete `ai` exporta `streamText`, `convertToModelMessages`, y el resultado tiene `toUIMessageStreamResponse`; `@ai-sdk/deepseek` exporta `deepseek`/`createDeepSeek`. If the API differs from the plan's assumption (e.g. `toDataStreamResponse` instead), NOTE it and adapt Tasks 3 accordingly — read `node_modules/ai/docs/` to confirm. Also confirm the installed `ai` major version (`node -p "require('ai/package.json').version"`).

- [ ] **Step 3: Typecheck + commit the deps**

Run: `npx tsc --noEmit`
Expected: clean (the new packages ship their own types).

```bash
git add package.json package-lock.json
git commit -m "chore(coach): instala AI SDK (ai) + provider DeepSeek (@ai-sdk/deepseek)"
```

---

## Task 2: System prompt (puro) + módulo de modelo

**Files:**
- Create: `lib/coach-prompt.ts`
- Create: `lib/coach-prompt.test.ts`
- Create: `lib/coach-model.ts`

- [ ] **Step 1: Write the failing test** — `lib/coach-prompt.test.ts`

```ts
import { describe, it, expect } from 'vitest';
import { systemPrompt, DISCLAIMER } from '@/lib/coach-prompt';
import type { CoachSnapshot } from '@/lib/coach-snapshot';

const snap: CoachSnapshot = {
  estancados: [{ ejercicio: 'Sentadilla', sesionesSinMejora: 4 }],
  semana: { sesiones: 2, objetivo: 3, volumen: 9000, deltaPct: 12, prs: [{ ejercicio: 'Press', tipo: 'peso' }] },
  grupos: [{ grupo: 'Pecho', volumenSemana: 1200, diasSinEntrenar: 2, objetivo: 1500 }],
};

describe('systemPrompt', () => {
  it('incluye el disclaimer, la persona de coach y los datos del snapshot serializados', () => {
    const p = systemPrompt(snap);
    expect(p).toContain(DISCLAIMER);
    expect(p.toLowerCase()).toContain('entrenador'); // persona de coach
    // el snapshot va serializado: aparecen datos concretos
    expect(p).toContain('Sentadilla');
    expect(p).toContain('Pecho');
    expect(p).toContain('"sesiones": 2');
  });

  it('es español y conciso (instruye respuestas accionables)', () => {
    const p = systemPrompt(snap);
    expect(p.toLowerCase()).toMatch(/español|concis|accionable/);
  });
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run lib/coach-prompt.test.ts`
Expected: FAIL — module missing.

- [ ] **Step 3: Implement** — `lib/coach-prompt.ts`

```ts
import type { CoachSnapshot } from '@/lib/coach-snapshot';

export const DISCLAIMER =
  'Esto es orientación general de entrenamiento, no consejo médico. Ante dolor o dudas de salud, consulta a un profesional.';

/** System prompt del coach: persona + datos del usuario (snapshot) + disclaimer. Puro. */
export function systemPrompt(snapshot: CoachSnapshot): string {
  return [
    'Eres un entrenador personal de fuerza e hipertrofia. Respondes en español, de forma concisa y accionable.',
    'Basas tus consejos en los DATOS del usuario que se incluyen abajo (sesiones, PRs, estancamientos, volumen por grupo y objetivos).',
    'Si te falta un dato, dilo; no inventes cifras. Da pasos concretos (peso, reps, descanso, sustituciones) cuando proceda.',
    '',
    'DATOS DEL USUARIO (JSON):',
    JSON.stringify(snapshot, null, 2),
    '',
    DISCLAIMER,
  ].join('\n');
}
```

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run lib/coach-prompt.test.ts`
Expected: PASS. Also `npx tsc --noEmit` clean.

- [ ] **Step 5: Implement the model module** — `lib/coach-model.ts`

```ts
import { deepseek } from '@ai-sdk/deepseek';

/** Modelo DeepSeek rápido/barato (V3 chat). Cambiar aquí para usar otro. */
export const MODELO_COACH = 'deepseek-chat';

/** El provider DeepSeek listo para `streamText({ model: modeloCoach(), ... })`. */
export function modeloCoach() {
  return deepseek(MODELO_COACH);
}

/** true si la API key de DeepSeek está configurada (solo servidor). */
export function deepseekConfigured(): boolean {
  return Boolean(process.env.DEEPSEEK_API_KEY);
}
```

- [ ] **Step 6: Typecheck**

Run: `npx tsc --noEmit`
Expected: clean.

- [ ] **Step 7: Commit**

```bash
git add lib/coach-prompt.ts lib/coach-prompt.test.ts lib/coach-model.ts
git commit -m "feat(coach): systemPrompt (snapshot→prompt) + módulo de modelo DeepSeek"
```

---

## Task 3: Ruta `POST /api/coach`

**Files:**
- Create: `app/api/coach/route.ts`
- Create: `app/api/coach/route.test.ts`

- [ ] **Step 0: READ FIRST**

Read `app/api/exercise-photos/route.ts` (the `auth()` 401 + `r2Configured()` 503 pattern) and `app/api/exercise-photos/route.test.ts` (how it mocks `@clerk/nextjs/server` `auth` and the dependency module). Read `node_modules/ai/docs/` to confirm `streamText`/`convertToModelMessages`/`toUIMessageStreamResponse` (per Task 1 verification).

- [ ] **Step 1: Write the failing test** — `app/api/coach/route.test.ts`

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest';

const auth = vi.fn();
vi.mock('@clerk/nextjs/server', () => ({ auth: () => auth() }));

const deepseekConfigured = vi.fn(() => true);
vi.mock('@/lib/coach-model', () => ({
  deepseekConfigured: () => deepseekConfigured(),
  modeloCoach: () => 'fake-model',
  MODELO_COACH: 'deepseek-chat',
}));

const streamText = vi.fn(() => ({ toUIMessageStreamResponse: () => new Response('stream-ok') }));
const convertToModelMessages = vi.fn((m: unknown) => m);
vi.mock('ai', () => ({
  streamText: (opts: unknown) => streamText(opts),
  convertToModelMessages: (m: unknown) => convertToModelMessages(m),
}));

import { POST } from '@/app/api/coach/route';

const snapshot = {
  estancados: [{ ejercicio: 'Sentadilla', sesionesSinMejora: 4 }],
  semana: { sesiones: 2, objetivo: 3, volumen: 9000, deltaPct: 12, prs: [] },
  grupos: [],
};

function req(body: unknown): Request {
  return new Request('http://localhost/api/coach', { method: 'POST', body: JSON.stringify(body) });
}

beforeEach(() => {
  auth.mockReset();
  deepseekConfigured.mockReturnValue(true);
  streamText.mockClear();
});

describe('POST /api/coach', () => {
  it('401 sin sesión', async () => {
    auth.mockResolvedValue({ userId: null });
    const res = await POST(req({ messages: [], snapshot }));
    expect(res.status).toBe(401);
  });

  it('503 si falta DEEPSEEK_API_KEY', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    deepseekConfigured.mockReturnValue(false);
    const res = await POST(req({ messages: [], snapshot }));
    expect(res.status).toBe(503);
  });

  it('con sesión + key: llama a streamText con el system prompt que contiene el snapshot y devuelve el stream', async () => {
    auth.mockResolvedValue({ userId: 'u1' });
    const res = await POST(req({ messages: [{ role: 'user', parts: [{ type: 'text', text: 'hola' }] }], snapshot }));
    expect(res.status).toBe(200);
    expect(streamText).toHaveBeenCalledTimes(1);
    const opts = streamText.mock.calls[0][0] as { system: string };
    expect(opts.system).toContain('Sentadilla'); // el snapshot se serializó en el system prompt
    expect(await res.text()).toBe('stream-ok');
  });
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run app/api/coach/route.test.ts`
Expected: FAIL — route missing.

- [ ] **Step 3: Implement** — `app/api/coach/route.ts`

```ts
import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { streamText, convertToModelMessages, type UIMessage } from 'ai';
import { modeloCoach, deepseekConfigured } from '@/lib/coach-model';
import { systemPrompt } from '@/lib/coach-prompt';
import type { CoachSnapshot } from '@/lib/coach-snapshot';

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });
  if (!deepseekConfigured()) return new NextResponse('Coach no configurado', { status: 503 });

  const { messages, snapshot } = (await req.json()) as { messages: UIMessage[]; snapshot: CoachSnapshot };

  const result = streamText({
    model: modeloCoach(),
    system: systemPrompt(snapshot),
    messages: convertToModelMessages(messages),
  });
  return result.toUIMessageStreamResponse();
}
```
If Task 1's verification showed a different streaming method name (e.g. `toDataStreamResponse`) or `UIMessage` import path, use the one confirmed in `node_modules/ai/docs/` and update the test mock's method name to match.

- [ ] **Step 4: Run, verify pass**

Run: `npx vitest run app/api/coach/route.test.ts`
Expected: PASS (401, 503, happy path). Also `npx tsc --noEmit` and `npx eslint app/api/coach/route.ts`.

- [ ] **Step 5: Commit**

```bash
git add app/api/coach/route.ts app/api/coach/route.test.ts
git commit -m "feat(coach): ruta POST /api/coach (Clerk + DeepSeek streamText + snapshot en el prompt)"
```

---

## Task 4: Verificación final

- [ ] **Step 1: Full suite**

Run: `npx vitest run`
Expected: todo verde (coach-prompt + route + previos).

- [ ] **Step 2: Types + lint**

Run: `npx tsc --noEmit && npm run lint`
Expected: sin errores.

- [ ] **Step 3: Build**

Run: `npm run build`
Expected: build OK (`--webpack`). (La ruta es server-only; `DEEPSEEK_API_KEY` no se referencia en cliente.)

---

## Self-Review (hecho)

**Cobertura del spec (sección "Ruta POST /api/coach"):**
- `auth()` → 401 sin sesión → Task 3. ✓
- 503 si falta `DEEPSEEK_API_KEY` → Task 3 (`deepseekConfigured`). ✓
- Body `{ mensajes, snapshot }`; system prompt = persona + snapshot + disclaimer → Tasks 2, 3. ✓
- `streamText` con DeepSeek (`DEEPSEEK_API_KEY` solo servidor) + streaming → Task 3. ✓
- Provider DeepSeek directo (no AI Gateway) → Tasks 1, 2 (`@ai-sdk/deepseek`). ✓
- Tests: 401 / 503 / happy-path con `streamText` mockeado verificando el snapshot en el prompt → Task 3; systemPrompt puro → Task 2. ✓

**Sin placeholders:** código completo. El único "verifica y ajusta" es por mandato del skill vercel:ai-sdk/AGENTS.md (la API del AI SDK debe confirmarse contra el paquete instalado) — no es un hueco, es diligencia obligada; la API documentada vigente está dada.

**Consistencia de tipos:** `CoachSnapshot` (de B2) consumido por `systemPrompt` y por la ruta. `modeloCoach()`/`deepseekConfigured()`/`MODELO_COACH` definidos en Task 2 y usados/mockeados en Task 3. Ruta usa `streamText`/`convertToModelMessages`/`toUIMessageStreamResponse` de `ai` (v6, confirmado). ✓

**Notas de deploy (no código):** añadir `DEEPSEEK_API_KEY` a Vercel Production (`vercel env add`) antes de desplegar; sin ella la ruta da 503. La migración Neon de `coach_messages` (B1) también debe correr antes del deploy de B.
