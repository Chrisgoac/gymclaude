# Coach B4 — Pantalla de chat /coach (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** La pantalla `/coach`: chat con streaming contra `/api/coach`, sembrado desde el hilo sincronizado (Dexie) y persistiendo cada mensaje, más chips de insight rápido.

**Architecture:** `useChat` (AI SDK v6, `@ai-sdk/react`) con `DefaultChatTransport({ api: '/api/coach' })`. Un wrapper lee el hilo de Dexie (`useLiveQuery(listMessages)`) y, una vez cargado, monta `<CoachChat seed={...} />` que llama a `useChat({ messages: seed })`. Al enviar: arma el snapshot (B2 `recogerSnapshot`), persiste el mensaje de usuario (B1 `addMessage`) y `sendMessage({text}, { body: { snapshot } })`; en `onFinish` persiste la respuesta del asistente. Chips de insight pre-rellenan/envían.

**Tech Stack:** Next.js 16 App Router · React 19 · AI SDK v6 (`@ai-sdk/react`, `DefaultChatTransport` de `ai`) · Dexie · Tailwind "Brutalist Iron" · Vitest + Testing Library.

**Nota (AGENTS.md + skill vercel:ai-sdk):** verificar la API de `useChat` contra `node_modules/@ai-sdk/react`. API vigente confirmada (ai-sdk.dev, v6): `useChat({ transport, messages, onFinish })` devuelve `{ messages, sendMessage, status, error, stop }`; **no** hay `input`/`handleSubmit` (gestiona el input con `useState`); `sendMessage({ text }, { body })`; `status ∈ 'ready'|'submitted'|'streaming'|'error'`; mensajes tienen `{ id, role, parts: [{type:'text', text}] }`. `DefaultChatTransport` se importa de `ai`.

Fase **B4** del spec `docs/superpowers/specs/2026-06-09-coach-ia-design.md` (sección "UI"). B1 (repo `lib/repositories/coach.ts`: `listMessages`/`addMessage`), B2 (`lib/coach-snapshot.ts`: `recogerSnapshot`), B3 (ruta) ya en main. B5 (accesos) después.

---

## File Structure

- **Modify** `package.json` — dep `@ai-sdk/react`.
- **Create** `components/coach-chat.tsx` — el componente de chat (recibe `seed`, usa useChat).
- **Create** `components/coach-chat.test.tsx` — test con useChat + repos mockeados.
- **Create** `app/coach/page.tsx` — wrapper: carga el hilo de Dexie y monta CoachChat.

---

## Task 1: Instalar `@ai-sdk/react`

**Files:**
- Modify: `package.json`

- [ ] **Step 1: Install**

Run: `npm install @ai-sdk/react`
If it fails on network (ConnectTimeout/fetch failed), report BLOCKED with the error.

- [ ] **Step 2: Verify the API**

Run:
```
node -p "require('@ai-sdk/react/package.json').version"
node -e "const r=require('@ai-sdk/react'); console.log('useChat:', typeof r.useChat)"
node -e "const a=require('ai'); console.log('DefaultChatTransport:', typeof a.DefaultChatTransport)"
```
Expected: `useChat` is a function; `DefaultChatTransport` is a function/class exported from `ai`. Report the actual outputs. If `DefaultChatTransport` is NOT in `ai` (e.g. it's in `@ai-sdk/react`), read `node_modules/@ai-sdk/react/dist` + `node_modules/ai/dist/index.d.ts` to find the correct import and REPORT it (Task 2 depends on it).

- [ ] **Step 3: Typecheck + commit**

Run: `npx tsc --noEmit`
```bash
git add package.json package-lock.json
git commit -m "chore(coach): instala @ai-sdk/react (hook useChat para el chat)"
```

## Report back
Status, the exact verification outputs (versions + useChat/DefaultChatTransport presence + correct import location), tsc result, commit SHA.

---

## Task 2: Componente `CoachChat` + página `/coach`

**Files:**
- Create: `components/coach-chat.tsx`
- Create: `components/coach-chat.test.tsx`
- Create: `app/coach/page.tsx`

- [ ] **Step 0: READ FIRST**

Read: `lib/repositories/coach.ts` (`listMessages`, `addMessage`, `CoachMessage`), `lib/coach-snapshot.ts` (`recogerSnapshot`), `lib/gym-filter.ts` (`useGymFilter`, `filtroAGymId`), `lib/coach-prompt.ts` (`DISCLAIMER`), `components/ui/button.tsx` + `components/ui/input.tsx` (props), and an existing client screen (e.g. `app/progreso/page.tsx`) for the Brutalist layout idiom. Confirm the `useChat`/`DefaultChatTransport` import locations from Task 1.

- [ ] **Step 1: Write the failing test** — `components/coach-chat.test.tsx`

Mock `@ai-sdk/react`'s `useChat`, the coach repo, and recogerSnapshot, so the component logic is tested without the network:

```tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const sendMessage = vi.fn();
const useChat = vi.fn();
vi.mock('@ai-sdk/react', () => ({ useChat: (opts: unknown) => useChat(opts) }));
vi.mock('ai', () => ({ DefaultChatTransport: class { constructor(public o: unknown) {} } }));

const addMessage = vi.fn().mockResolvedValue(undefined);
vi.mock('@/lib/repositories/coach', () => ({ addMessage: (...a: unknown[]) => addMessage(...a) }));

const recogerSnapshot = vi.fn().mockResolvedValue({ estancados: [], semana: { sesiones: 0, objetivo: 3, volumen: 0, deltaPct: null, prs: [] }, grupos: [] });
vi.mock('@/lib/coach-snapshot', () => ({ recogerSnapshot: (...a: unknown[]) => recogerSnapshot(...a) }));

import { CoachChat } from '@/components/coach-chat';

beforeEach(() => {
  sendMessage.mockReset();
  addMessage.mockReset().mockResolvedValue(undefined);
  recogerSnapshot.mockReset().mockResolvedValue({ estancados: [], semana: { sesiones: 0, objetivo: 3, volumen: 0, deltaPct: null, prs: [] }, grupos: [] });
  useChat.mockReturnValue({ messages: [], sendMessage, status: 'ready', error: undefined });
});

it('renderiza el hilo sembrado', () => {
  useChat.mockReturnValue({
    messages: [
      { id: 'a', role: 'user', parts: [{ type: 'text', text: '¿subo peso?' }] },
      { id: 'b', role: 'assistant', parts: [{ type: 'text', text: 'Sí, +2.5kg' }] },
    ],
    sendMessage, status: 'ready', error: undefined,
  });
  render(<CoachChat seed={[]} />);
  expect(screen.getByText('¿subo peso?')).toBeInTheDocument();
  expect(screen.getByText('Sí, +2.5kg')).toBeInTheDocument();
});

it('al enviar: persiste el mensaje del usuario y llama a sendMessage con el snapshot', async () => {
  render(<CoachChat seed={[]} />);
  await userEvent.type(screen.getByPlaceholderText(/pregunta/i), 'hola coach');
  await userEvent.click(screen.getByRole('button', { name: /enviar/i }));
  expect(addMessage).toHaveBeenCalledWith('user', 'hola coach');
  expect(recogerSnapshot).toHaveBeenCalled();
  expect(sendMessage).toHaveBeenCalledTimes(1);
  const [msg, opts] = sendMessage.mock.calls[0];
  expect(msg).toEqual({ text: 'hola coach' });
  expect(opts.body.snapshot).toBeDefined();
});

it('un chip de insight rápido envía su pregunta', async () => {
  render(<CoachChat seed={[]} />);
  await userEvent.click(screen.getByRole('button', { name: 'Analiza mi semana' }));
  expect(sendMessage).toHaveBeenCalledTimes(1);
  expect(sendMessage.mock.calls[0][0]).toEqual({ text: 'Analiza mi semana' });
});

it('muestra el disclaimer', () => {
  render(<CoachChat seed={[]} />);
  expect(screen.getByText(/no consejo médico/i)).toBeInTheDocument();
});
```

- [ ] **Step 2: Run, verify fail**

Run: `npx vitest run components/coach-chat.test.tsx`
Expected: FAIL — component missing.

- [ ] **Step 3: Implement** — `components/coach-chat.tsx`

```tsx
'use client';

import { useState } from 'react';
import { useChat } from '@ai-sdk/react';
import { DefaultChatTransport } from 'ai';
import type { CoachMessage } from '@/lib/db/types';
import { addMessage } from '@/lib/repositories/coach';
import { recogerSnapshot } from '@/lib/coach-snapshot';
import { useGymFilter, filtroAGymId } from '@/lib/gym-filter';
import { DISCLAIMER } from '@/lib/coach-prompt';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

const INSIGHTS = ['Analiza mi semana', '¿Por qué estoy estancado?', '¿Qué ejercicio sustituyo?'];

/** Extrae el texto de un UIMessage (concatena sus partes de texto). */
function textoDe(m: { parts?: { type: string; text?: string }[] }): string {
  return (m.parts ?? []).filter((p) => p.type === 'text').map((p) => p.text ?? '').join('');
}

export function CoachChat({ seed }: { seed: CoachMessage[] }) {
  const [filtro] = useGymFilter();
  const gymId = filtroAGymId(filtro);
  const [texto, setTexto] = useState('');

  const { messages, sendMessage, status } = useChat({
    transport: new DefaultChatTransport({ api: '/api/coach' }),
    messages: seed.map((m) => ({ id: m.id, role: m.rol, parts: [{ type: 'text' as const, text: m.contenido }] })),
    onFinish: ({ message }: { message: { parts?: { type: string; text?: string }[] } }) => {
      const t = textoDe(message);
      if (t) void addMessage('assistant', t);
    },
  });

  const ocupado = status === 'submitted' || status === 'streaming';

  async function enviar(pregunta: string) {
    const q = pregunta.trim();
    if (!q || ocupado) return;
    setTexto('');
    await addMessage('user', q);
    const snapshot = await recogerSnapshot(gymId);
    void sendMessage({ text: q }, { body: { snapshot } });
  }

  return (
    <div className="space-y-4">
      <div className="space-y-3">
        {messages.map((m) => (
          <div key={m.id} className={m.role === 'user' ? 'text-right' : ''}>
            <span className={`inline-block whitespace-pre-wrap brutal-box px-3 py-2 text-sm ${m.role === 'user' ? 'bg-primary text-primary-foreground' : 'bg-card'}`}>
              {textoDe(m)}
            </span>
          </div>
        ))}
        {ocupado && <p className="label-mono text-[10px] text-muted-foreground">El coach está pensando…</p>}
      </div>

      <div className="flex flex-wrap gap-2">
        {INSIGHTS.map((q) => (
          <button key={q} type="button" onClick={() => void enviar(q)} disabled={ocupado}
            className="label-mono border-2 border-foreground bg-card px-2 py-1 text-[10px] disabled:opacity-50">
            {q}
          </button>
        ))}
      </div>

      <form
        className="flex items-end gap-2"
        onSubmit={(e) => { e.preventDefault(); void enviar(texto); }}
      >
        <Input
          placeholder="Pregunta a tu coach…"
          value={texto}
          onChange={(e) => setTexto(e.target.value)}
          className="flex-1"
        />
        <Button type="submit" disabled={ocupado || texto.trim() === ''}>Enviar</Button>
      </form>

      <p className="label-mono text-[10px] text-muted-foreground">{DISCLAIMER}</p>
    </div>
  );
}
```
(If `useChat`'s option for seeding initial messages is named differently in the installed version — Task 1 — or `onFinish`'s arg shape differs, adjust per `node_modules/@ai-sdk/react` and report. `m.role` from useChat is `'user'|'assistant'|'system'`; our seed only uses user/assistant, and `CoachMessage.rol` is `'user'|'assistant'` — the map is fine.)

- [ ] **Step 4: Create the page** — `app/coach/page.tsx`

```tsx
'use client';

import { useLiveQuery } from 'dexie-react-hooks';
import { listMessages } from '@/lib/repositories/coach';
import { CoachChat } from '@/components/coach-chat';

export default function CoachPage() {
  const hilo = useLiveQuery(() => listMessages(), []);
  return (
    <div className="space-y-6">
      <div>
        <p className="label-mono text-[11px] text-muted-foreground">Tu entrenador IA</p>
        <h1 className="text-5xl">Coach</h1>
      </div>
      {hilo === undefined ? (
        <p className="text-muted-foreground">Cargando…</p>
      ) : (
        <CoachChat seed={hilo} />
      )}
    </div>
  );
}
```
(The page seeds `CoachChat` ONCE from the loaded Dexie thread — `useChat` initializes its `messages` from `seed` on first mount. New messages within the session are managed by `useChat` + persisted to Dexie; on reload the thread re-seeds.)

- [ ] **Step 5: Run, verify pass**

Run: `npx vitest run components/coach-chat.test.tsx`
Expected: PASS (4). Also `npx tsc --noEmit` and `npx eslint components/coach-chat.tsx app/coach/page.tsx`.

- [ ] **Step 6: Commit**

```bash
git add components/coach-chat.tsx components/coach-chat.test.tsx app/coach/page.tsx
git commit -m "feat(coach): pantalla /coach (chat streaming sembrado/persistido + insights rápidos)"
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
Expected: build OK (`--webpack`). `/coach` aparece como ruta.

---

## Self-Review (hecho)

**Cobertura del spec (sección "UI", parte chat):**
- Pantalla `/coach` con streaming (`useChat`) sembrada desde el hilo de Dexie → Tasks 1, 2 (página). ✓
- Persistir cada mensaje en Dexie (usuario al enviar, asistente en onFinish) → Task 2. ✓
- Snapshot enviado por petición (`recogerSnapshot` + body) → Task 2. ✓
- Insights rápidos (3 chips que envían su pregunta) → Task 2. ✓
- Disclaimer visible → Task 2. ✓
- Estilo Brutalist (brutal-box, label-mono, Button/Input) → Task 2. ✓
- Tests: render del hilo sembrado, envío (persiste user + snapshot), chip, disclaimer → Task 2. ✓
- (Acceso desde cabecera/Home = B5, no aquí.)

**Sin placeholders:** código completo. Los "ajusta si la versión instalada difiere" son diligencia obligada del AI SDK (AGENTS.md), con la API vigente dada.

**Consistencia de tipos:** `CoachMessage` (B1) → `seed` → mapeado a UIMessage `{id, role, parts}`. `addMessage('user'|'assistant', texto)` (B1). `recogerSnapshot(gymId)` (B2) → `body.snapshot`, consumido por la ruta (B3) como `CoachSnapshot`. `useChat`/`DefaultChatTransport`/`sendMessage`/`status` de la API v6 confirmada. ✓

**Decisiones:** el hilo de Dexie es la fuente de verdad entre sesiones; dentro de una sesión `useChat` gobierna la UI y persistimos a Dexie en paralelo (al recargar, re-siembra). Un mensaje que llegue por sync de otro dispositivo a mitad de sesión no aparece hasta recargar (aceptable, uso personal). El `gymId` del filtro activo acota el snapshot. Manejo del input con `useState` (v6 quitó `input`/`handleSubmit`).
