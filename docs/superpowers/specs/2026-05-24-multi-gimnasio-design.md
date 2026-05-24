# GymLog — Multi-gimnasio (entrenos y análisis por gimnasio)

- **Fecha:** 2026-05-24
- **Estado:** Aprobado (pendiente de revisión final del usuario)
- **Depende de:** app GymLog completa (Fases 1–4B) ya desplegada.

## Resumen

El usuario entrena en **varios gimnasios** y, según el día, va a uno u otro. Los pesos
difieren entre gimnasios (máquinas, discos, longitud de palanca…), así que mezclar todo
en una sola gráfica de progreso o en los PRs distorsiona la lectura.

Esta feature añade el concepto de **gimnasio**: cada entreno se etiqueta con el gimnasio
en el que se hizo, y todas las vistas de análisis (gráfica de progreso, récords, historial
y volumen por músculo) se pueden **filtrar por gimnasio**. El autorrelleno del último peso
pasa a ser **por gimnasio**, que es lo que de verdad hace útil la separación.

Es coherente con la arquitectura existente: **local-first** (Dexie) con **sync multi-dispositivo**
(Clerk + Neon + Drizzle), tombstones y LWW por `updatedAt`. Estética **Brutalist Iron**.

## Decisiones tomadas (brainstorming)

| Tema | Decisión |
|------|----------|
| Granularidad | **Por entreno (sesión)**: cada entreno se hace en un gimnasio; todas sus series quedan etiquetadas con él. |
| Alcance del filtro | **Todo el análisis**: un selector global filtra gráfica de progreso, PRs, historial y volumen por músculo. |
| Autorrelleno | **Por gimnasio**: al entrenar en Gym B, prerellena con el último peso/reps de ese ejercicio en Gym B. |
| Selección al empezar | **Preguntar siempre**: al iniciar un entreno se elige el gimnasio explícitamente. |
| Datos previos | **Backfill a uno**: migración única que asigna todos los entrenos sin gimnasio a uno elegido; después, gimnasio **obligatorio** en cada entreno nuevo. |

## Enfoque

Entidad `Gym` sincronizada + campo `gymId` en `WorkoutSession`; las stats filtran *sesiones*
por gimnasio. Descartados por YAGNI: denormalizar `gymId` en cada serie (duplica datos y
tráfico de sync) y una entidad "baseline por gym+ejercicio" (filtrar por sesión ya da
progreso y PRs por gimnasio).

## Modelo de datos

### Nueva entidad `Gym` (`lib/db/types.ts`)
```ts
export interface Gym extends SyncMeta {
  userId: string | null;   // null en local; el servidor asigna el del usuario
  nombre: string;
  orden: number;            // para ordenar la lista en Ajustes y los chips de filtro
  archivada: boolean;       // oculto de selección pero conserva su histórico
}
```

### `WorkoutSession` gana `gymId`
```ts
gymId: string | null;       // null = "Sin gimnasio" (solo datos pre-migración)
```

### Dexie v5 (`lib/db/database.ts`)
- Nueva tabla: `gyms: 'id, userId, nombre, deletedAt'`.
- Reindexar `workoutSessions` añadiendo `gymId`:
  `'id, userId, routineDayId, gymId, fecha, deletedAt'`.
- La migración de versión **no transforma datos**: las sesiones existentes quedan con
  `gymId` ausente (tratado como `null`). El backfill (ver abajo) es una acción de usuario,
  no parte del upgrade de Dexie.

### Drizzle / Neon (`db/schema.ts` + `drizzle-kit push`)
- Nueva tabla `gyms` con las columnas `sync` comunes (`id, user_id, updated_at, deleted_at,
  server_updated_at`) + `nombre text notNull`, `orden integer notNull`, `archivada boolean notNull`.
- Nueva columna en `workout_sessions`: `gym_id text` (nullable).

## Sincronización

- Registrar `gyms` en los tres registros del motor de sync:
  - `lib/sync/collect.ts` → `SYNCABLE_TABLES` (se sincroniza siempre, como `routines`; sin `shouldSync`).
  - `lib/sync/apply.ts` → `TABLE_BY_NAME`.
  - `lib/sync/server-tables.ts` → `SERVER_TABLES`.
- `gymId` viaja **dentro** del registro de `workoutSessions`: el motor (`collectDirty`/`applyIncoming`,
  push/pull) copia el objeto entero, así que no requiere cambios. Solo hay que crear la columna
  `gym_id` en Postgres para que el upsert la persista.
- Orden de aplicación: como `gyms` y `workoutSessions` son entidades independientes y el merge es
  por registro (LWW), no hay requisito de orden estricto; una sesión puede referenciar un `gymId`
  cuyo `Gym` aún no haya llegado; la UI resuelve el nombre cuando llegue.

## Repositorio de gimnasios (`lib/repositories/gyms.ts`)

- `listGyms()` — activos (no borrados), ordenados por `orden`.
- `createGym(nombre)` — `orden` = nº de gimnasios actuales.
- `renameGym(id, nombre)`.
- `archiveGym(id, archivada)` — togglea `archivada` (oculto de selección, conserva histórico).
- `softDeleteGym(id)` — tombstone; **no toca las sesiones** que lo referencian.
- `reorderGyms(idsEnOrden)` — actualiza `orden`.
- Todas las escrituras hacen bump de `updatedAt` (para que sincronicen).

**Borrado:** soft-delete del gimnasio no borra sus entrenos. Las sesiones cuyo `gymId`
apunte a un gimnasio borrado se muestran como "Sin gimnasio".

## Arranque de entreno (preguntar siempre)

- `startSession({ routineDayId?, gymId })` pasa a **requerir `gymId`**.
- UI (`components/start-workout.tsx`): tanto "Empezar entreno libre" como "Empezar" de un
  día de rutina abren primero un **selector de gimnasio** (lista de gimnasios activos como
  chips/botones brutalistas). Al elegir, se crea la sesión con ese `gymId` y se navega a
  `/entrenar/[sessionId]`.
- **Sin gimnasios todavía:** el selector muestra un estado vacío con CTA "Crea tu primer
  gimnasio" que lleva a la sección Gimnasios de Ajustes. (Un único camino: gestionar los
  gimnasios siempre se hace en Ajustes.)
- **Autorrelleno por gimnasio:** `getLastSet` recibe el gimnasio de la sesión actual y filtra
  las sesiones candidatas a ese `gymId`:
  `getLastSet(exerciseId, { excludeSessionId, gymId })`. La tarjeta de registro
  (`logged-exercise-card.tsx`) lee el `gymId` de la sesión y lo pasa.

## Filtro global de análisis

- Estado `gymFilter`: `'all' | <gymId>`, guardado en `localStorage` (clave p. ej.
  `gymlog.gymFilter`). Es preferencia de **vista**, no se sincroniza. Hook compartido
  `useGymFilter()` para leer/escribir.
- Selector visible arriba de **Progreso** y **Historial**, compartiendo el mismo estado:
  chips cuadrados brutalistas `Todos · Gym A · Gym B …` (gimnasios activos + "Todos").
- Stats reciben el filtro y restringen por las sesiones del gimnasio:
  - `getExerciseProgress(exerciseId, gymId?)`
  - `getExercisePRs(exerciseId, gymId?)`
  - `getVolumeByMuscle(sinceTs, gymId?)`
  - `listSessionSummaries(gymId?)`
  - Semántica: `gymId` ausente o `'all'` ⇒ todas las sesiones; un id ⇒ solo sesiones con ese
    `gymId`. Implementación: construir el set de `sessionIds` del gimnasio y filtrar como ya
    se hace hoy con `sessionIds`.

## Gestión + backfill (Ajustes)

- **Sección "Gimnasios"** en `app/ajustes/page.tsx`: lista de gimnasios con crear, renombrar,
  archivar/borrar y reordenar.
- **Backfill único (migración de datos del usuario):** si existen sesiones activas con
  `gymId == null`, mostrar un aviso (una vez) en Ajustes: "Tienes N entrenos sin gimnasio →
  asígnalos a [selector de gimnasio]". Al confirmar, se asigna ese `gymId` a todas las
  sesiones nulas y se hace bump de `updatedAt` (para que sincronicen). Requiere tener al
  menos un gimnasio creado. Tras el backfill no quedan sesiones sin gimnasio.
- **Reasignar gimnasio de un entreno pasado:** en el detalle de sesión del historial
  (`app/historial/[sessionId]/page.tsx`), un control para cambiar el `gymId` de ese entreno.

## Etiquetado en la UI

- Nombre del gimnasio visible en: resúmenes del historial (`session-summary-list.tsx`),
  detalle de sesión y, opcionalmente, cabecera de la pantalla de entreno en curso.
- Resolución de nombre: helper que, dado un `gymId`, devuelve el nombre del gimnasio activo
  o "Sin gimnasio" si es `null` o apunta a uno borrado.

## Tests

- **Repo `gyms`**: CRUD; `softDeleteGym` no borra ni modifica las sesiones que lo referencian.
- **`getLastSet` por gimnasio**: con sesiones del mismo ejercicio en Gym A y Gym B, prerellena
  con la última de cada gimnasio según el `gymId` pasado.
- **Stats con filtro**: `getExerciseProgress` / `getExercisePRs` / `getVolumeByMuscle` /
  `listSessionSummaries` devuelven lo correcto para un gimnasio concreto vs `'all'`.
- **Backfill**: asigna `gymId` a todas las sesiones con `gymId == null` y bump de `updatedAt`;
  no toca las que ya tenían gimnasio.
- **Migración Dexie v5**: datos previos intactos; `gymId` indexable; tabla `gyms` operativa.
- Mantener verde la suite existente (71 tests) y los textos exactos que asertan
  (botones/labels) salvo que el test se actualice a propósito.

## Fuera de alcance (YAGNI)

- Colores/iconos por gimnasio.
- Gimnasio "principal" o por defecto recordado (se eligió "preguntar siempre").
- Geolocalización / autodetección de gimnasio.
- Comparativas cruzadas entre gimnasios (mismo ejercicio en dos gimnasios superpuesto).

## Notas de implementación

- Build con `--webpack` (Serwist). Tras tocar `db/schema.ts`: `npm run db:push`.
- Mantener la estética Brutalist Iron en los nuevos componentes (chips cuadrados, borde 2px,
  sombra dura, `label-mono`).
- Ver specs/planes previos en `docs/superpowers/`.
