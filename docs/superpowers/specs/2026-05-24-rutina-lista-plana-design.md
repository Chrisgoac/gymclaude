# GymLog — Rutina como lista plana de ejercicios (eliminar la capa "día")

- **Fecha:** 2026-05-24
- **Estado:** Aprobado (pendiente de revisión final del usuario)
- **Depende de:** app GymLog completa (Fases 1–4B + Brutalist Iron + Multi-gimnasio) en `main`.

## Resumen

Hoy una rutina se organiza en **días** y los ejercicios cuelgan de cada día
(`Routine → RoutineDay → RoutineExercise`). Para añadir un ejercicio hay que crear
primero un "día", lo cual no es obvio y añade fricción. El usuario quiere que una
**rutina sea directamente una lista de ejercicios** (sin la capa intermedia de día),
asumiendo que se pierden los splits multi-día.

Esta feature **elimina `RoutineDay` del modelo**: `RoutineExercise` pasa a referenciar
`routineId`. Es un cambio de modelo de datos con migración en Dexie (local) y en
Drizzle/Neon (servidor), más ajustes de sync y UI. Estética **Brutalist Iron**.

## Decisión (brainstorming)

- Granularidad elegida (vía AskUserQuestion): **ejercicios directos a la rutina**, perdiendo
  los splits multi-día.
- Enfoque elegido (vía AskUserQuestion): **B — eliminar el día del modelo** (modelo limpio con
  migración), en vez de A (mantener un "día oculto" interno sin cambios de esquema).

## Modelo de datos

### `RoutineExercise` (`lib/db/types.ts`)
- Reemplazar `routineDayId: string` por `routineId: string`. El resto de campos
  (`exerciseId`, `orden`, `seriesObjetivo`, `repsObjetivo`, `descansoSegundos`, `notas`) intacto.

### `RoutineDay`
- **Eliminado por completo**: se quita la interface `RoutineDay`, la tabla Dexie `routineDays`
  y la tabla Drizzle `routineDays`/`routine_days`. Los nombres/notas de día se descartan
  (decisión aceptada).

### `WorkoutSession.routineDayId`
- Se mantiene como **campo legacy nullable, ya no se escribe**. No se renombra a `routineId`
  para evitar una segunda migración de `workout_sessions` (recién tocada por `gymId`). Queda
  documentado como obsoleto; ninguna lógica nueva lo lee ni lo escribe.

## Migración

### Dexie v6 (`lib/db/database.ts`)
- `upgrade`: para cada `routineExercise` existente, fijar `routineId` = el `routineId` de su
  `routineDay` actual (lookup en la tabla `routineDays` antes de borrarla), y hacer bump de
  `updatedAt` (para que el cambio re-sincronice al servidor).
- Reindexar `routineExercises` a `'id, routineId, exerciseId, orden, deletedAt'`.
- Eliminar la tabla local `routineDays` (`routineDays: null` en `version(6).stores`).
- El resto de tablas se mantienen igual que en v5 (incluida `gyms`).
- Nota: el `upgrade` debe leer el mapa día→routineId **antes** de que la tabla `routineDays`
  desaparezca; Dexie ejecuta el `upgrade` con acceso a los datos previos.

### Drizzle/Neon (`db/schema.ts` + `drizzle-kit push`)
- En `routineExercises`: añadir `routineId: text('routine_id')` (**nullable en el servidor**) y
  quitar `routineDayId`/`routine_day_id`. Nota: nullable a propósito — añadir una columna
  `NOT NULL` a una tabla con filas existentes falla en Postgres; el cliente siempre rellena
  `routineId` (invariante de tipo en TS: `routineId: string`) y re-sincroniza las filas migradas.
- Eliminar la tabla `routineDays`/`routine_days` del esquema.
- `db:push` (ejecutado por el controlador, gestionando los prompts): elegir **crear `routine_id`
  + dropear `routine_day_id`** (NO "rename", porque los valores antiguos son ids de día, no de
  rutina) y **dropear la tabla `routine_days`**. Los `routine_exercises` re-sincronizan
  `routineId` desde el cliente (que ya lo migró con `updatedAt` bumpeado).
- ⚠️ **DDL destructivo en producción**: dropea `routine_days` y `routine_day_id`. Aceptable: esos
  datos (días) se descartan a propósito; el contenido real (ejercicios) migra a `routineId`.
  Conviene redeployar (`vercel --prod`) poco después.

### Sync (`lib/sync/`)
- Quitar `routineDays` de `SYNCABLE_TABLES` (`collect.ts`), `TABLE_BY_NAME` (`apply.ts`) y
  `SERVER_TABLES` (`server-tables.ts`).
- `routineExercises` sigue sincronizando; su nuevo `routineId` viaja dentro del registro (el
  motor copia el objeto entero, sin cambios).

## Repositorio (`lib/repositories/routines.ts`)

- **Eliminar**: `addDay`, `listDays`, `updateDay`, `softDeleteDay`, `addExerciseToDay`,
  `listDayExercises`.
- **Añadir**:
  - `addExerciseToRoutine(routineId, input)` — `orden` = nº de ejercicios activos de la rutina;
    crea el `RoutineExercise` con `routineId`. `input` mantiene
    `{ exerciseId, seriesObjetivo?, repsObjetivo?, descansoSegundos?, notas? }`.
  - `listRoutineExercises(routineId)` — ejercicios activos de la rutina, ordenados por `orden`.
- **Mantener**: `createRoutine`, `getRoutine`, `listRoutines`, `updateRoutine`,
  `updateRoutineExercise`, `softDeleteRoutineExercise`.
- `softDeleteRoutine(id)` — cascada rutina → sus `routineExercises` (where `routineId` = id),
  sin la capa día.

## Sesión de entreno (`lib/repositories/workouts.ts`)

- `startSession({ routineId?, gymId? })` — si hay `routineId`, precargar los ejercicios con
  `listRoutineExercises(routineId)` (mismo patrón que antes pero por rutina). Quitar el preload
  por `routineDayId`. La sesión NO persiste el routineId (solo se usa para precargar; coherente
  con que `WorkoutSession.routineDayId` queda legacy sin uso).

## UI

- **Editor de rutina** (`app/rutinas/[id]/page.tsx`): sustituir la sección "Días" por la lista
  de ejercicios de la rutina (`listRoutineExercises`), reusando `RoutineDayExerciseRow` para
  cada ejercicio (objetivos + quitar), más un botón **"Añadir ejercicio"** → `/rutinas/[id]/anadir`.
  Mantener "Borrar rutina". Estética Brutalist Iron.
- **Nueva página** `app/rutinas/[id]/anadir/page.tsx`: buscador de ejercicios (copiado del de
  día) que llama a `addExerciseToRoutine(routineId, ...)` y vuelve a `/rutinas/[id]`.
- **Eliminar** las rutas `app/rutinas/dia/[dayId]/page.tsx` y
  `app/rutinas/dia/[dayId]/anadir/page.tsx` (y la carpeta `app/rutinas/dia/`).
- **Home "Empezar"** (`components/start-workout.tsx`): pasar de rutinas→días→Empezar a
  **rutinas→Empezar** (un botón por rutina), que dispara el selector de gimnasio existente y
  luego `startSession({ routineId, gymId })`.
- `routine-day-exercise-row.tsx` se mantiene (edita objetivos de un `RoutineExercise`); su nombre
  puede quedarse o renombrarse a `routine-exercise-row` (opcional, no obligatorio).

## Tests

- **Repo `routines`** (reescrito a `routineId`): `addExerciseToRoutine` apila con `orden`
  incremental; `listRoutineExercises` ordena y filtra borrados; `softDeleteRoutine` cascada a
  sus ejercicios.
- **Migración Dexie v6**: sembrar (con la BD v5) una rutina + día + ejercicio, abrir la v6 y
  comprobar que el `routineExercise` tiene `routineId` correcto y que `db.routineDays` ya no
  existe. (Si testear el `upgrade` real es complejo en fake-indexeddb, como mínimo verificar que
  `listRoutineExercises` funciona y que el esquema v6 no expone `routineDays`.)
- **`startSession({ routineId })`** precarga los ejercicios de la rutina.
- **`start-workout`**: test actualizado al flujo rutina→Empezar→gimnasio.
- Reescribir/actualizar los tests existentes que usaban la capa día:
  `lib/repositories/routines.test.ts`, `lib/repositories/workouts.test.ts` (usos de `addDay`/
  `addExerciseToDay`/`startSession({routineDayId})`), `app/rutinas/[id]/page.test.tsx`.
- Mantener verde el resto de la suite; build `--webpack` OK.

## Fuera de alcance (YAGNI)

- No renombrar `WorkoutSession.routineDayId` (queda legacy nullable).
- No reordenar ejercicios dentro de la rutina (no existe hoy).
- No conservar la tabla `routine_days` huérfana (se elimina; el usuario aprobó el drop).

## Notas de implementación

- Build con `--webpack` (Serwist). Tras tocar `db/schema.ts`: `npm run db:push` (controlador,
  gestionando prompts; elegir create+drop, no rename).
- Mantener la estética Brutalist Iron en las páginas de rutina reescritas.
- Rama de trabajo nueva (no `main` directo). Merge a `main` al terminar.
