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

## Variables de entorno (servidor)

Solo nombres; los valores viven en Vercel (y en `.env.local` para desarrollo).

- `DATABASE_URL` (Postgres/Neon) · `MIGRATION_DATABASE_URL` + `CONFIRM_TARGET` (solo scripts `db:migrate:*`).
- Clerk: `NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY`, `CLERK_SECRET_KEY`.
- R2: `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET`, `R2_PUBLIC_URL`, `R2_PRIVATE_BUCKET` (medios v1; sin ella → 503 `MEDIA_UNAVAILABLE`).
- `CRON_SECRET` (crons de `vercel.json`) · `DEEPSEEK_API_KEY` (IA).
- Google Play: `GOOGLE_PLAY_PACKAGE_NAME`, `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, `GOOGLE_PLAY_RTDN_AUDIENCE`, `GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT`.
- `PRO_USER_IDS` (opcional): ids de usuario de Clerk separados por comas (`user_…`), Pro de cortesía sin compra en Play. Lo respetan el gate de IA (`lib/entitlements/reader.ts`) y `GET /api/v1/billing/entitlement`, que responde `PRO`/`ACTIVE` sin `productId` con `validUntil` móvil de 7 días. Vacía o ausente = nadie. Quitar un id deja de dar Pro en el siguiente despliegue (la app Android lo deja de mostrar como mucho 7 días después sin conexión).

## Datos

Todo vive en IndexedDB (base `gymlog`) mediante Dexie. El catálogo de ejercicios se siembra en el primer arranque. Cada registro lleva `id` (UUID), `updatedAt` y `deletedAt` (borrado lógico), preparados para la sincronización de la Fase 4.

## Contrato de sincronización v1 (Android)

La API `/api/v1/sync/{push,pull}` para el cliente Android nativo está documentada en [`contracts/v1/README.md`](contracts/v1/README.md); los vectores de prueba compartidos están en `contracts/v1/fixtures/` y los valida `lib/api/v1/contracts/fixtures.test.ts`.
