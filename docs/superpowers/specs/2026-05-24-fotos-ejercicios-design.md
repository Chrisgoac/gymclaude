# GymLog — Fotos de ejercicios + lista mejorada

- **Fecha:** 2026-05-24
- **Estado:** Aprobado (pendiente de revisión final del usuario)
- **Depende de:** app GymLog completa (Fases 1–4B + Brutalist Iron + Multi-gimnasio + Rutina plana) en `main`, desplegada.

## Resumen

Poder asociar **una foto a cada ejercicio** (del catálogo precargado o propio), con las fotos
**sincronizadas en la nube** vía **Cloudflare R2** (S3-compatible), y mejorar la **lista de
ejercicios** (miniaturas + filtros rápidos). Las miniaturas también se ven al entrenar y en el
editor de rutina. Estética **Brutalist Iron**.

## Decisiones (brainstorming)

| Tema | Decisión |
|------|----------|
| Almacenamiento | **Cloudflare R2** (sincronizado/multi-dispositivo), no local. |
| Alcance | Foto en **todos** los ejercicios (catálogo incluido). |
| Modelo | **Entidad `ExercisePhoto` única** que mapea `exerciseId → foto` (el catálogo es read-only/no-sincroniza, así que la foto no puede vivir en el registro `Exercise`). |
| Subida | **Ruta server-proxy** Next.js autenticada con Clerk (credenciales R2 solo en servidor). |
| Lista | Filas con **miniatura** a la izquierda + **chips de filtro** (grupo muscular y equipamiento) junto al buscador. |
| Extras | Miniatura **al entrenar y en rutinas**; **comprimir** la imagen en cliente antes de subir; **editar/quitar** foto. |
| Una por ejercicio | Sí (1 foto activa por ejercicio). |

## Prerrequisito (provisión por el usuario)

Antes de poder subir fotos en producción/local, el usuario crea en Cloudflare:
- Un **bucket R2** con **acceso público** (URL pública `*.r2.dev` o dominio propio).
- Un **token de API R2** con credenciales S3 (Access Key ID + Secret Access Key).

Y define estas variables de entorno (en `.env.local` y en Vercel):
- `R2_ACCOUNT_ID`
- `R2_ACCESS_KEY_ID`
- `R2_SECRET_ACCESS_KEY`
- `R2_BUCKET`
- `R2_PUBLIC_URL` (base pública del bucket, p. ej. `https://pub-xxxx.r2.dev`)

La implementación incluirá los pasos exactos. Mientras no estén las credenciales, la ruta de
subida responde 503 con un mensaje claro; el resto de la app funciona igual.

## Modelo de datos

### Nueva entidad `ExercisePhoto` (`lib/db/types.ts`)
```ts
export interface ExercisePhoto extends SyncMeta {
  userId: string | null;   // null en local; el servidor asigna el del usuario
  exerciseId: string;      // a qué ejercicio pertenece
  url: string;             // URL pública en R2 (para <img>)
  key: string;             // object key en R2 (para borrar/reemplazar)
}
```
- **Dexie v8**: tabla `exercisePhotos: 'id, exerciseId, deletedAt'`. (Resto de tablas heredadas de v7.)
- **Drizzle/Neon**: tabla `exercise_photos` con las columnas `sync` comunes + `exercise_id text notNull`, `url text notNull`, `key text notNull`. `drizzle-kit push` aditivo (sin prompts).
- **Sync**: registrar `exercisePhotos` en `collect.ts`, `apply.ts`, `server-tables.ts` (se sincroniza siempre, como `gyms`). Los registros son pequeños (URLs/keys, no bytes de imagen).
- **Invariante**: una sola `ExercisePhoto` activa por `exerciseId` y usuario.

## Almacenamiento Cloudflare R2

- Dependencia nueva: `@aws-sdk/client-s3` (cliente S3 apuntando al endpoint R2
  `https://<accountid>.r2.cloudflarestorage.com`).
- Cliente S3 server-side en `lib/r2/client.ts` (lee las env vars; si faltan, las rutas devuelven 503).
- **Ruta de subida** `app/api/exercise-photos/route.ts`:
  - `POST` (Clerk `auth()` → 401 sin sesión): recibe **`multipart/form-data`** con los campos `file`
    (el Blob JPEG comprimido) y `exerciseId` (vía `await req.formData()`); genera un `key`
    (`<userId>/<exerciseId>/<uuid>.jpg`), hace `PutObject` a R2 (`ContentType: image/jpeg`), devuelve
    `{ url, key }` donde `url = ${R2_PUBLIC_URL}/${key}`.
  - `DELETE` (Clerk auth): recibe `{ key }`, hace `DeleteObject` en R2 (best-effort; ignora "not found").
- No se guarda nada en Neon desde la ruta: el cliente, tras recibir `{url,key}`, llama a `setPhoto`
  (Dexie) y el motor de sync ya existente persiste el `ExercisePhoto` en Neon.

## Compresión en cliente

- `lib/image/compress.ts`: `compressImage(file: File): Promise<Blob>` usando `<canvas>` (sin dependencia):
  carga la imagen, la redimensiona manteniendo proporción a **máx 1024px** el lado mayor, y exporta
  **JPEG calidad ~0.8** vía `canvas.toBlob`. Devuelve el Blob a subir.

## Repositorio `lib/repositories/exercise-photos.ts`

- `setPhoto(exerciseId, { url, key })` — upsert: si ya existe una foto activa para ese `exerciseId`,
  actualiza `url`/`key` (y bump `updatedAt`); si no, crea una nueva. Devuelve el `key` anterior (si lo
  había) para que la UI borre el objeto R2 huérfano.
- `removePhoto(exerciseId)` — soft-delete del `ExercisePhoto`; devuelve el `key` para borrar en R2.
- `getPhoto(exerciseId)` — la `ExercisePhoto` activa o `undefined`.
- `getPhotosMap()` — `Map<exerciseId, ExercisePhoto>` de las activas (para pintar miniaturas en listas).

## UI

- **`components/exercise-photo-picker.tsx`**: dado un `exerciseId`, muestra la foto actual (o un
  placeholder cuadrado con ícono) + botón "Añadir/Cambiar foto" (input `accept="image/*"`) y "Quitar".
  Flujo al elegir archivo: `compressImage` → `POST /api/exercise-photos` → `setPhoto` (y `DELETE`
  del key anterior si lo había). "Quitar": `removePhoto` + `DELETE` del key. Si `navigator.onLine`
  es falso, deshabilita y avisa ("necesitas conexión para subir fotos").
- **`components/exercise-list.tsx`**: filas con **miniatura** a la izquierda (foto o placeholder),
  manteniendo el agrupado por músculo. Añadir **chips de filtro** por grupo muscular y por
  equipamiento (multi-toggle, estado local del componente) que se combinan con el buscador de texto.
- **Detalle de ejercicio** (`app/ejercicios/[id]/page.tsx`): montar `ExercisePhotoPicker` tanto en la
  vista editable (ejercicio propio) como en la read-only (catálogo) — los campos del catálogo siguen
  siendo solo lectura, pero la foto se puede poner.
- **`components/logged-exercise-card.tsx`**: miniatura pequeña junto al nombre del ejercicio.
- **Editor de rutina** (`components/routine-day-exercise-row.tsx`): miniatura pequeña junto al nombre.
- Las listas que pintan miniaturas obtienen el mapa con `useLiveQuery(() => getPhotosMap())`.

## Service worker (offline de imágenes)

- En `app/sw.ts` (Serwist), añadir una regla de **runtime caching** `CacheFirst` para las peticiones
  de imagen al host de `R2_PUBLIC_URL`, para que las fotos ya vistas se muestren offline. (Si añadir
  reglas de runtime caching resulta intrusivo en la config actual de Serwist, queda como mejora menor;
  el resto del feature no depende de ello.)

## Backup

- Incluir `exercisePhotos` en `lib/repositories/backup.ts` (export/import) y subir `version` a 6.
  (Solo metadatos URL/key; los bytes viven en R2.)

## Tests

- **Repo `exercise-photos`**: `setPhoto` crea; segundo `setPhoto` para el mismo `exerciseId` actualiza
  (no duplica) y devuelve el key anterior; `removePhoto` soft-delete; `getPhotosMap` mapea solo activas.
- **`compress`**: dada una imagen grande simulada, el Blob resultante respeta el lado máx (se puede
  testear la lógica de cálculo de dimensiones de forma aislada; el `canvas` real en jsdom es limitado,
  así que extraer y testear `calcularDimensiones(w, h, max)` por separado).
- **Ruta `/api/exercise-photos`**: 401 sin sesión (mock de Clerk `auth`); con sesión y env presentes,
  sube (mock del cliente S3) y devuelve `{url,key}`; 503 si faltan las env vars.
- **Lista**: filtra por chip de grupo/equipamiento combinado con el buscador; pinta miniatura cuando
  hay foto en el mapa.
- **Sync**: `collectDirty` incluye `exercisePhotos`.
- Mantener verdes los 88 tests existentes.

## Fuera de alcance (YAGNI)

- Varias fotos por ejercicio / galería.
- Recorte/edición manual de la imagen (solo redimensionar+comprimir automático).
- Caché avanzada / borrado de objetos R2 huérfanos por GC (se borra el objeto al reemplazar/quitar de
  forma best-effort; no hay recolección periódica).

## Notas de implementación

- Build con `--webpack` (Serwist). Tras tocar `db/schema.ts`: `npm run db:push` (aditivo, sin prompts).
- Rama de trabajo nueva; merge a `main` al terminar; el `db:push` (aditivo, no destructivo) puede
  ejecutarse sin acoplar al deploy. Redeploy con `vercel --prod` al final.
- Mantener la estética Brutalist Iron (miniaturas con borde 2px, placeholder cuadrado, chips como en
  `gym-filter`).
