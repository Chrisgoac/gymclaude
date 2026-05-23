# GymLog — Fase 4B: Wiring de nube (Clerk + Neon + deploy) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Conectar el motor de sync de la 4A a servicios reales: migrar el esquema a Neon, endpoints `/api/sync/push|pull` autenticados con Clerk, login con Clerk (modelo opt-in: la app sigue funcionando local sin cuenta; al iniciar sesión, sincroniza), transporte HTTP, disparador de sync con indicador de estado, y despliegue en Vercel.

**Architecture:** El servidor (Neon, vía `@neondatabase/serverless` + Drizzle) es la fuente de verdad. El **push** autentica con Clerk, ignora cualquier `userId` del cliente y sella `userId` (de la sesión) + `serverUpdatedAt` (reloj servidor); resuelve con `resolveServerWrite` (LWW por `updatedAt` de cliente). El **pull** devuelve filas del `userId` con `serverUpdatedAt >= cursor`. El cliente usa `runSync` (4A) con un transporte HTTP. Modelo de auth **opt-in**: sin login la app es local; con login (`useAuth().isSignedIn`) el `SyncProvider` ejecuta `runSync` al montar, al volver online y cada 30 s. Migraciones con `drizzle-kit push` usando `DATABASE_URL_UNPOOLED` (sin pgbouncer).

**Tech Stack:** Next.js 16 · TS · @clerk/nextjs · Drizzle ORM + @neondatabase/serverless · drizzle-kit · dotenv.

**Spec:** `docs/superpowers/specs/2026-05-23-gymlog-design.md` · **Fase previa:** 4A (motor de sync). `.env.local` ya tiene `DATABASE_URL`, `DATABASE_URL_UNPOOLED`, `NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY`, `CLERK_SECRET_KEY`.

**Notas:** las claves Clerk son `_live_` (producción) → el login real funciona en el dominio desplegado, no necesariamente en localhost. Verificación local: migración + build + tests unitarios; el round-trip auth se valida tras el deploy.

---

## File Structure

- `drizzle.config.ts` — cargar `.env.local` + usar URL unpooled
- `db/client.ts` — cliente Drizzle (neon-http)
- `lib/sync/server-tables.ts` — registro nombre→tabla Drizzle
- `app/api/sync/push/route.ts` — endpoint push (Clerk + upsert LWW)
- `app/api/sync/pull/route.ts` — endpoint pull (Clerk + filtra por userId/cursor)
- `lib/sync/http-transport.ts` — `SyncTransport` sobre fetch
- `components/sync-provider.tsx` — dispara `runSync` + indicador de estado
- `components/auth-header.tsx` — barra con SignIn/UserButton
- `middleware.ts` — `clerkMiddleware()`
- `app/layout.tsx` — envolver en `<ClerkProvider>`, montar header + SyncProvider
- `package.json` — script `db:push`

---

## Task 1: Migración del esquema a Neon

**Files:** Modify `drizzle.config.ts`; modify `package.json`.

- [ ] **Step 1: Instalar dotenv**

```bash
npm install -D dotenv
```

- [ ] **Step 2: Actualizar `drizzle.config.ts`** para cargar `.env.local` y usar la URL unpooled (las migraciones DDL no van bien por pgbouncer)

```ts
import { config } from 'dotenv';
import { defineConfig } from 'drizzle-kit';

config({ path: '.env.local' });

export default defineConfig({
  schema: './db/schema.ts',
  out: './db/migrations',
  dialect: 'postgresql',
  dbCredentials: {
    url: process.env.DATABASE_URL_UNPOOLED ?? process.env.DATABASE_URL ?? '',
  },
});
```

- [ ] **Step 3: Añadir script en `package.json`**

```json
"db:push": "drizzle-kit push"
```

- [ ] **Step 4: Crear las tablas en Neon**

Run: `npm run db:push`
Expected: drizzle-kit conecta a Neon y crea las 7 tablas (`exercises`, `routines`, `routine_days`, `routine_exercises`, `workout_sessions`, `logged_exercises`, `logged_sets`). Responde "yes"/aplica si pregunta.

- [ ] **Step 5: Verificar**

Run: `node --env-file=.env.local -e "const {neon}=require('@neondatabase/serverless'); const sql=neon(process.env.DATABASE_URL); sql\`select table_name from information_schema.tables where table_schema='public' order by table_name\`.then(r=>{console.log(r.map(x=>x.table_name)); process.exit(0)})"`
Expected: lista con las 7 tablas.

- [ ] **Step 6: Commit**

```bash
git add drizzle.config.ts package.json package-lock.json && git commit -m "feat: migrate sync schema to Neon (drizzle-kit push)"
```
(No commitear `.env.local` ni `db/migrations` con credenciales — `.gitignore` ya ignora `.env*`; añade `db/migrations` solo si no contiene secretos.)

---

## Task 2: Cliente Drizzle + registro de tablas + endpoints de sync

**Files:** Create `db/client.ts`, `lib/sync/server-tables.ts`, `app/api/sync/push/route.ts`, `app/api/sync/pull/route.ts`.

- [ ] **Step 1: Crear `db/client.ts`**

```ts
import { drizzle } from 'drizzle-orm/neon-http';
import { neon } from '@neondatabase/serverless';
import * as schema from './schema';

const sql = neon(process.env.DATABASE_URL!);
export const db = drizzle(sql, { schema });
```

- [ ] **Step 2: Crear `lib/sync/server-tables.ts`**

```ts
import type { PgTable } from 'drizzle-orm/pg-core';
import * as schema from '@/db/schema';

export const SERVER_TABLES: Record<string, PgTable> = {
  exercises: schema.exercises,
  routines: schema.routines,
  routineDays: schema.routineDays,
  routineExercises: schema.routineExercises,
  workoutSessions: schema.workoutSessions,
  loggedExercises: schema.loggedExercises,
  loggedSets: schema.loggedSets,
};
```

- [ ] **Step 3: Crear `app/api/sync/push/route.ts`**

Drizzle genérico sobre `PgTable` requiere casts; se aísla con un eslint-disable de fichero, justificado.

```ts
/* eslint-disable @typescript-eslint/no-explicit-any */
import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { and, eq } from 'drizzle-orm';
import { db } from '@/db/client';
import { SERVER_TABLES } from '@/lib/sync/server-tables';
import { resolveServerWrite } from '@/lib/sync/server-merge';
import type { TableChanges } from '@/lib/sync/types';

export async function POST(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });

  const changes = (await req.json()) as TableChanges[];
  const serverUpdatedAt = Date.now();

  for (const { table: name, records } of changes) {
    const table = SERVER_TABLES[name] as any;
    if (!table) continue;
    for (const rec of records as any[]) {
      const existing = await db
        .select({ updatedAt: table.updatedAt })
        .from(table)
        .where(and(eq(table.id, rec.id), eq(table.userId, userId)))
        .limit(1);
      if (!resolveServerWrite(existing[0]?.updatedAt, rec.updatedAt)) continue;
      const values = { ...rec, userId, serverUpdatedAt };
      await db.insert(table).values(values).onConflictDoUpdate({ target: table.id, set: values });
    }
  }
  return NextResponse.json({ ok: true });
}
```

- [ ] **Step 4: Crear `app/api/sync/pull/route.ts`**

```ts
/* eslint-disable @typescript-eslint/no-explicit-any */
import { auth } from '@clerk/nextjs/server';
import { NextResponse } from 'next/server';
import { and, eq, gte } from 'drizzle-orm';
import { db } from '@/db/client';
import { SERVER_TABLES } from '@/lib/sync/server-tables';
import type { TableChanges } from '@/lib/sync/types';

export async function GET(req: Request) {
  const { userId } = await auth();
  if (!userId) return new NextResponse('Unauthorized', { status: 401 });

  const cursor = Number(new URL(req.url).searchParams.get('cursor') ?? '0');
  const changes: TableChanges[] = [];
  let maxSeen = cursor;

  for (const [name, t] of Object.entries(SERVER_TABLES)) {
    const table = t as any;
    const rows = (await db
      .select()
      .from(table)
      .where(and(eq(table.userId, userId), gte(table.serverUpdatedAt, cursor)))) as any[];
    if (rows.length === 0) continue;
    for (const r of rows) if (r.serverUpdatedAt > maxSeen) maxSeen = r.serverUpdatedAt;
    // Quita la columna server-only antes de enviar al cliente.
    const records = rows.map(({ serverUpdatedAt: _s, ...rest }) => rest);
    changes.push({ table: name, records });
  }
  return NextResponse.json({ changes, cursor: maxSeen });
}
```

- [ ] **Step 5: Typecheck + commit**

Run: `npx tsc --noEmit` → limpio.
```bash
git add -A && git commit -m "feat: authenticated sync API routes (push/pull) with Drizzle + Clerk"
```

---

## Task 3: Clerk (provider, middleware, header) + transporte + SyncProvider

**Files:** Install `@clerk/nextjs`; create `middleware.ts`, `lib/sync/http-transport.ts`, `components/sync-provider.tsx`, `components/auth-header.tsx`; modify `app/layout.tsx`.

- [ ] **Step 1: Instalar Clerk**

```bash
npm install @clerk/nextjs
```

- [ ] **Step 2: Crear `middleware.ts`** (en la raíz del repo)

```ts
import { clerkMiddleware } from '@clerk/nextjs/server';

export default clerkMiddleware();

export const config = {
  matcher: ['/((?!_next|sw\\.js|manifest\\.webmanifest|icon-.*\\.png|.*\\.[\\w]+$).*)', '/(api|trpc)(.*)'],
};
```

- [ ] **Step 3: Crear `lib/sync/http-transport.ts`**

```ts
import type { SyncTransport } from './types';

export const httpTransport: SyncTransport = {
  async push(changes) {
    const res = await fetch('/api/sync/push', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(changes),
    });
    if (!res.ok) throw new Error(`push falló: ${res.status}`);
  },
  async pull(cursor) {
    const res = await fetch(`/api/sync/pull?cursor=${cursor}`);
    if (!res.ok) throw new Error(`pull falló: ${res.status}`);
    return res.json();
  },
};
```

- [ ] **Step 4: Crear `components/sync-provider.tsx`** (dispara runSync + indicador)

```tsx
'use client';

import { useEffect, useState } from 'react';
import { useAuth } from '@clerk/nextjs';
import { runSync } from '@/lib/sync/sync';
import { httpTransport } from '@/lib/sync/http-transport';

type Estado = 'idle' | 'syncing' | 'offline' | 'error';

export function SyncProvider() {
  const { isSignedIn } = useAuth();
  const [estado, setEstado] = useState<Estado>('idle');

  useEffect(() => {
    if (!isSignedIn) return;
    let cancelado = false;

    async function sync() {
      if (typeof navigator !== 'undefined' && !navigator.onLine) {
        if (!cancelado) setEstado('offline');
        return;
      }
      if (!cancelado) setEstado('syncing');
      try {
        await runSync(httpTransport);
        if (!cancelado) setEstado('idle');
      } catch {
        if (!cancelado) setEstado('error');
      }
    }

    void sync();
    const onOnline = () => void sync();
    window.addEventListener('online', onOnline);
    const intervalo = setInterval(() => void sync(), 30000);
    return () => {
      cancelado = true;
      window.removeEventListener('online', onOnline);
      clearInterval(intervalo);
    };
  }, [isSignedIn]);

  if (!isSignedIn) return null;
  const etiqueta =
    estado === 'syncing' ? 'Sincronizando…' : estado === 'offline' ? 'Sin conexión' : estado === 'error' ? 'Error de sync' : 'Sincronizado';
  return <span className="text-xs text-muted-foreground">{etiqueta}</span>;
}
```

- [ ] **Step 5: Crear `components/auth-header.tsx`**

```tsx
'use client';

import { SignInButton, SignedIn, SignedOut, UserButton } from '@clerk/nextjs';
import { SyncProvider } from '@/components/sync-provider';

export function AuthHeader() {
  return (
    <header className="mx-auto flex max-w-md items-center justify-between p-4 pb-0">
      <span className="text-sm font-bold">GymLog</span>
      <div className="flex items-center gap-3">
        <SyncProvider />
        <SignedOut>
          <SignInButton mode="modal">
            <button className="text-sm text-primary">Iniciar sesión</button>
          </SignInButton>
        </SignedOut>
        <SignedIn>
          <UserButton />
        </SignedIn>
      </div>
    </header>
  );
}
```

- [ ] **Step 6: Envolver `app/layout.tsx` en `<ClerkProvider>` y montar el header**

Importa `import { ClerkProvider } from '@clerk/nextjs';` y `import { AuthHeader } from '@/components/auth-header';`. Envuelve TODO el árbol del `<body>` con `<ClerkProvider>` y añade `<AuthHeader />` antes del `<DbProvider>`:

```tsx
return (
  <html lang="es" className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}>
    <body className="min-h-screen pb-16 flex flex-col">
      <ClerkProvider>
        <AuthHeader />
        <DbProvider>
          <main className="mx-auto max-w-md p-4">{children}</main>
        </DbProvider>
        <BottomNav />
      </ClerkProvider>
    </body>
  </html>
);
```

- [ ] **Step 7: Verificación**

Run: `npx tsc --noEmit` → limpio.
Run: `npm run lint` → sin errores ni warnings.
Run: `npm test` → 71 tests siguen verdes (Clerk/rutas no afectan a los tests existentes).
Run: `npm run build` → pasa (Clerk necesita `NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY` en build; está en `.env.local`, pero `next build` no lo carga solo — usa `node --env-file=.env.local ./node_modules/.bin/next build` si el build se queja de la clave, o confía en que Vercel la inyecta en el deploy).

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: Clerk auth (opt-in) + HTTP sync transport + sync trigger/indicator"
```

---

## Task 4: Smoke test local + despliegue

**Files:** ninguno nuevo (operación).

- [ ] **Step 1: Arrancar en local y comprobar que la app sigue funcionando sin login**

Run: `node --env-file=.env.local ./node_modules/.bin/next dev` (o `npm run dev` si Next carga `.env.local`, que sí lo hace).
Comprueba en el navegador: la app carga, el catálogo/rutinas/registro funcionan **sin** iniciar sesión (modo local). El header muestra "Iniciar sesión".
(Con claves `_live_` el login en localhost puede redirigir al dominio de producción; el round-trip real se valida tras el deploy.)

- [ ] **Step 2: Desplegar a producción** (PARAR Y CONFIRMAR con el usuario antes de ejecutar)

Run: `vercel deploy --prod`
Vercel ya tiene las variables (Clerk + Neon) en el entorno Production. Espera a que termine y da la URL.

- [ ] **Step 3: Verificación end-to-end en la URL desplegada**

- Abrir la URL, **iniciar sesión** con Clerk.
- Crear un ejercicio/rutina; comprobar el indicador "Sincronizado".
- Abrir en otro dispositivo/navegador, iniciar sesión con la misma cuenta → los datos aparecen (pull).
- Verificar en Neon que hay filas (p. ej. `select count(*) from routines`).

- [ ] **Step 4: Commit (si hubo ajustes) y cierre**

```bash
git add -A && git commit -m "chore: deploy GymLog to production" || true
```

---

## Self-Review (cobertura — Fase 4B)

- **Esquema en Neon (migración)** → Task 1 ✅
- **Endpoints push/pull autenticados (Clerk userId del servidor, no del cliente)** → Task 2 ✅
- **LWW en servidor + sello de `serverUpdatedAt`** → Task 2 (`resolveServerWrite`, upsert) ✅
- **Login Clerk opt-in (la app sigue local sin cuenta)** → Task 3 ✅
- **Transporte HTTP que implementa `SyncTransport`** → Task 3 ✅
- **Disparo de `runSync` (montaje/online/intervalo) + indicador de estado** → Task 3 ✅
- **Despliegue en Vercel** → Task 4 ✅
- **Diferido/aceptado:** sync tras cada mutación (se usa intervalo 30 s + online); claves `_live_` (login real en el dominio desplegado); unificar `useParams()`.

Sin placeholders. Casts de Drizzle genérico aislados con `eslint-disable` justificado en los dos route handlers.

## Notas de seguridad

- `.env.local` está ignorado por git; nunca commitear secretos.
- Rotar el `sk_live_` de Clerk y la password de Neon expuestos en el chat.
- El push ignora `userId` del cliente y usa el de la sesión Clerk → un usuario no puede escribir datos de otro.
