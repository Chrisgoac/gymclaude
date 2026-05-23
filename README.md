# GymLog

App personal de gimnasio (rutinas, pesos y entrenos). PWA móvil **local-first**: funciona offline en el gimnasio y guarda los datos en el dispositivo (IndexedDB). En fases posteriores tendrá cuenta y sincronización en la nube.

## Estado por fases

- **Fase 1 (actual):** cimientos + catálogo de ejercicios (explorar, buscar, crear/editar/borrar los propios), instalable como PWA y offline.
- Fase 2 — rutinas + registro de entrenos.
- Fase 3 — progreso, historial y export/import.
- Fase 4 — cuenta (Clerk) + sincronización (Neon/Drizzle).

Diseño y planes: `docs/superpowers/specs/` y `docs/superpowers/plans/`.

## Stack

Next.js 16 (App Router, TS) · Tailwind v4 · shadcn/ui · Dexie (IndexedDB) · Serwist (PWA) · Vitest + React Testing Library.

## Desarrollo

```bash
npm install
npm run dev        # http://localhost:3000 (Turbopack)
npm test           # tests unitarios y de componente (Vitest)
npm run build      # build de producción con webpack (genera public/sw.js vía Serwist)
npm run lint
```

> Nota: `dev` usa Turbopack; `build` usa `--webpack` porque el service worker de Serwist se empaqueta con el plugin de webpack. El SW está deshabilitado en desarrollo.

## Datos

Todo vive en IndexedDB (base `gymlog`) mediante Dexie. El catálogo de ejercicios se siembra en el primer arranque. Cada registro lleva `id` (UUID), `updatedAt` y `deletedAt` (borrado lógico), preparados para la sincronización de la Fase 4.
