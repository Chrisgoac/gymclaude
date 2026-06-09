# Coach B5 — Accesos al coach (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hacer el coach accesible: un icono ✨ en la cabecera (visible en toda la app) y una tarjeta "Pregunta al coach" en Home, ambos enlazando a `/coach`.

**Architecture:** Dos cambios de UI puros (Links a `/coach`): un icono en `components/auth-header.tsx` (mismo patrón que el icono de Ajustes) y una tarjeta brutalist en `app/page.tsx`. Sin lógica, sin datos.

**Tech Stack:** Next.js 16 App Router · Tailwind "Brutalist Iron" · Lucide icons.

**Nota:** Fase **B5** (última) del spec `docs/superpowers/specs/2026-06-09-coach-ia-design.md` (sección "Acceso"). La pantalla `/coach` (B4) ya existe. No añade pestaña a la navbar (decisión del usuario).

---

## File Structure

- **Modify** `components/auth-header.tsx` — icono Coach (Sparkles) enlazando a `/coach`, junto al de Ajustes.
- **Modify** `app/page.tsx` — tarjeta "Pregunta al coach" enlazando a `/coach`.

---

## Task 1: Icono del coach en la cabecera + tarjeta en Home

**Files:**
- Modify: `components/auth-header.tsx`
- Modify: `app/page.tsx`

> UI pura (Links). Verificación por tsc/lint/build; no requiere test unitario (no hay tests de auth-header ni de la home; son enlaces de navegación).

- [ ] **Step 0: READ FIRST**

Read `components/auth-header.tsx` (the existing Ajustes `<Link href="/ajustes" aria-label="Ajustes">` with the `Settings` Lucide icon + active-state styling via `usePathname`) and `app/page.tsx` (the header block + `<WeeklyDigestMini />` + `<StartWorkout />`). Confirm `Sparkles`/`MessageCircle` exists in the installed `lucide-react` (the project already imports Lucide icons; `Sparkles` is standard).

- [ ] **Step 1: Add the Coach icon to the header** — in `components/auth-header.tsx`:

1. Add `Sparkles` to the lucide import: change `import { Settings } from 'lucide-react';` to `import { Settings, Sparkles } from 'lucide-react';`
2. Add a `coachActivo` flag next to `ajustesActivo`:
```ts
  const ajustesActivo = pathname.startsWith('/ajustes');
  const coachActivo = pathname.startsWith('/coach');
```
3. In the right-side `<div className="flex items-center gap-3">`, add a Coach Link BEFORE the Ajustes Link (so order is Coach, Ajustes, Sync, user), mirroring the Ajustes Link exactly:
```tsx
          <Link
            href="/coach"
            aria-label="Coach IA"
            className={`grid size-8 place-items-center border-2 border-foreground transition-transform active:translate-x-[1px] active:translate-y-[1px] ${
              coachActivo ? 'bg-primary text-primary-foreground' : 'bg-card text-foreground'
            }`}
          >
            <Sparkles className="size-4" strokeWidth={2} aria-hidden="true" />
          </Link>
```

- [ ] **Step 2: Add the Home card** — in `app/page.tsx`:

1. Add imports:
```ts
import Link from 'next/link';
import { Sparkles } from 'lucide-react';
```
2. Add a card BETWEEN `<WeeklyDigestMini />` and `<StartWorkout />` (a brutal-box Link to /coach with the press-feedback idiom):
```tsx
      <Link
        href="/coach"
        aria-label="Pregunta al coach"
        className="brutal-box flex items-center justify-between gap-3 px-3 py-2.5 transition-transform active:translate-x-[2px] active:translate-y-[2px]"
      >
        <span className="flex items-center gap-2">
          <Sparkles className="size-4 text-primary" strokeWidth={2} aria-hidden="true" />
          <span className="font-semibold">Pregunta al coach</span>
        </span>
        <span className="label-mono text-[10px] text-muted-foreground">IA</span>
      </Link>
```
Adapt the exact placement to the real `app/page.tsx` structure (after the WeeklyDigestMini line, before StartWorkout).

- [ ] **Step 3: Typecheck + lint + build**

Run: `npx tsc --noEmit && npx eslint components/auth-header.tsx app/page.tsx && npm run build`
Expected: clean; `/coach` reachable; build OK.

- [ ] **Step 4: Commit**

```bash
git add components/auth-header.tsx app/page.tsx
git commit -m "feat(coach): acceso al coach (icono en cabecera + tarjeta en Home)"
```

---

## Task 2: Verificación final

- [ ] **Step 1: Full suite + types + lint + build**

Run: `npx vitest run && npx tsc --noEmit && npm run lint && npm run build`
Expected: todo verde; rutas `/coach` y `/api/coach` presentes en el build.

---

## Self-Review (hecho)

**Cobertura del spec (sección "Acceso"):**
- Icono en `auth-header` (junto al de Ajustes) → `/coach` → Task 1. ✓
- Tarjeta "Pregunta al coach" en Home → `/coach` → Task 1. ✓
- NO se añade pestaña a la navbar → respetado (solo header + Home). ✓

**Sin placeholders:** código completo de ambos enlaces.

**Consistencia:** ambos enlazan a `/coach` (la página de B4). El icono de cabecera replica el patrón del de Ajustes (active-state por `usePathname`, `aria-label`, press-feedback). La tarjeta de Home usa `brutal-box` + el patrón de feedback de pulsación como `weekly-digest-mini`. `Sparkles` de `lucide-react` (ya en uso en el repo).

**Nota de cierre de B:** con B5 el roadmap A→C→B está completo en código. Para que el coach funcione en producción quedan los pasos de deploy (no-código): (1) `vercel env add DEEPSEEK_API_KEY` (Production), (2) `node scripts/migrate-coach-messages.mjs` contra Neon, (3) `vercel --prod`.
