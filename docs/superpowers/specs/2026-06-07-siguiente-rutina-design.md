# Diseño — "Última rutina hecha" y sugerencia de "siguiente rutina"

Fecha: 2026-06-07
Estado: aprobado (decisiones vía AskUserQuestion)

## Objetivo

En la pantalla **Entrenar**, ayudar al usuario a saber qué rutina le toca:

1. **Siempre**: mostrar cuál fue la **última rutina** que hizo y cuándo (ej. "hace 3 días").
2. **Opcional (toggle en Ajustes)**: sugerir la **siguiente rutina en orden de rotación** (la que va justo después de la última, en ciclo).

El orden de rotación lo define el usuario manualmente (reordenar rutinas, como ya se hace con los gimnasios). Si el toggle está desactivado, **no** se sugiere siguiente: solo se muestra la info de la última.

## Decisiones del usuario

- **Modelo**: rotación en orden fijo (A→B→C→A…). La sugerencia se **habilita/deshabilita desde Ajustes**. Si está off, solo info de la última hecha.
- **Orden**: **manual propio** (campo `orden` en `Routine` + reordenar con flechas, patrón gimnasios).
- **Presentación**: **tarjeta "Siguiente" arriba** en Entrenar, con la rutina sugerida y su botón Empezar, más una línea pequeña "Última: X · hace N días". Debajo, la lista normal de rutinas.

## Hallazgo clave (estado actual)

`WorkoutSession` **no guarda de qué rutina vino**. `startSession({ routineId })` precarga ejercicios pero descarta el `routineId`. Por tanto el vínculo se empieza a registrar **a partir de ahora**; los entrenos antiguos quedan sin rutina (no cuentan como "última rutina"). Esto es aceptable: el usuario verá la sugerencia en cuanto haga 1 entreno desde una rutina.

## Cambios de datos

### 1. `WorkoutSession.routineId?: string | null`
- Nuevo campo opcional. `startSession` lo guarda (null/ausente en entreno libre).
- Servidor (Drizzle `db/schema.ts`): nueva columna `routine_id text('routine_id')` en `workout_sessions`. **Requiere DDL aditivo en Neon** (`ALTER TABLE workout_sessions ADD COLUMN routine_id text`).
- Sync: viaja **dentro del objeto** `workoutSessions` (igual que `gymId`); no hace falta tocar `collect`/`apply`/`server-tables`, solo la columna servidor para que sobreviva el round-trip.
- Dexie: campo no indexado; **no requiere** bump de versión por sí mismo (Dexie guarda campos no indexados libremente). Se consultará la última sesión con `routineId` escaneando `listSessions()` (ya ordenadas por fecha desc).

### 2. `Routine.orden: number`
- Nuevo campo obligatorio (como `Gym.orden`).
- **Dexie v9**: upgrade que **rellena `orden`** en las rutinas existentes (por nombre alfabético, para un orden inicial estable) y bump `updatedAt`. (No es necesario indexarlo; se ordena en memoria.)
- Servidor: `routines` se sincroniza por objeto completo → `orden` viaja sin columna nueva **solo si** la tabla servidor admite el campo. Revisar `db/schema.ts` tabla `routines`: añadir columna `orden integer` si el upsert mapea columnas explícitas (aditivo en Neon). *(A confirmar en el plan: si el server-write hace upsert columna a columna, `orden` necesita columna; si guarda blob, no.)*

## Cambios de repositorio

### `lib/repositories/routines.ts`
- `createRoutine`: asigna `orden = nº de rutinas activas` (append al final).
- `listRoutines`: ordenar por `orden` asc (desempate por `nombre`). **Ojo**: cambia el orden actual (alfabético) → revisar tests de `routine-list`.
- Nueva `reorderRoutines(idsEnOrden: string[])`: reescribe `orden` por índice (idéntico a `reorderGyms`).

### `lib/repositories/workouts.ts`
- `startSession`: persistir `routineId: input.routineId ?? null` en la sesión.
- Nueva `getLastRoutineSession(): Promise<WorkoutSession | undefined>`: primera sesión activa (orden fecha desc) con `routineId` no nulo. *(Opcional: aceptar `gymId?` para respetar el filtro de gimnasio; decidir en plan — por defecto NO filtra, "última rutina" es global.)*

### `lib/routine-rotation.ts` (nuevo, función pura testeable)
- `getNextRoutineId(routinesEnOrden: {id}[], lastRoutineId: string | null): string | null`
  - Si no hay rutinas → null.
  - Si `lastRoutineId` no está en la lista (borrada) o es null → primera rutina.
  - Si está → la siguiente módulo longitud (ciclo).

## Ajuste/Setting (preferencia local)

`lib/settings.ts` (nuevo), patrón idéntico a `lib/gym-filter.ts` (localStorage + `useSyncExternalStore`):
- Clave `gymlog.suggestNextRoutine`, boolean, **default `false`**.
- `getSuggestNextRoutine()`, `setSuggestNextRoutine(v)`, hook `useSuggestNextRoutine(): [boolean, (v)=>void]`.
- Es una preferencia **local del dispositivo** (no se sincroniza), coherente con `gym-filter`.

## UI

### Ajustes (`app/ajustes/page.tsx`)
- **Toggle** "Sugerir siguiente rutina (rotación)" usando `useSuggestNextRoutine` (checkbox/switch con estilo brutalista existente).
- **Reordenar rutinas**: nuevo componente `RoutineOrderManager` (o sección en uno existente) con lista de rutinas y flechas ▲▼ que llaman a `reorderRoutines`. Patrón visual = `GymManager` (`brutal-box`, `divide-y-2`).

### Entrenar (`components/start-workout.tsx`)
- Cargar `routines` (ya), `getLastRoutineSession()`, y el setting.
- Calcular `nextRoutineId = getNextRoutineId(routines, lastSession?.routineId ?? null)`.
- **Tarjeta "Siguiente" arriba** (`brutal-box`, destacada):
  - Si `suggestNextRoutine === true` y hay `nextRoutine`: título mono "SIGUIENTE", nombre de la rutina grande, botón **Empezar {nombre}** (reusa el flujo `setPendiente({tipo:'rutina', routineId})` → GymPicker). Debajo, línea pequeña: "Última: {nombre última} · {hace N días}".
  - Si `suggestNextRoutine === false`: **no** tarjeta de siguiente; mostrar solo la línea "Última: {nombre} · hace N días" (discreta, encima del botón de entreno libre). Si no hay última rutina registrada, omitir la línea.
- El resto (botón "Empezar entreno libre" + lista "Desde una rutina") se mantiene igual.

### Formato de fecha relativa
- Helper `formatHaceDias(ts)` → "hoy", "ayer", "hace N días", o fecha corta si >N. (Reusar si ya existe alguno en Historial; si no, pequeño helper local.)

## Backup

Las rutinas se serializan como objeto completo → `orden` se incluye automáticamente. `routineId` en sesiones idem. No se cambia el formato salvo bump de `version` por prudencia (de 6 → 7) si la validación de import es estricta; **a confirmar** leyendo `backup.ts` (importar rutinas sin `orden` antiguas debe tolerarse → default 0).

## Tests

- `routine-rotation.test.ts`: `getNextRoutineId` (vacío, last null, last desconocido, ciclo, una sola rutina).
- `routines` repo: `createRoutine` asigna orden incremental; `listRoutines` ordena por orden; `reorderRoutines` reescribe.
- `workouts` repo: `startSession` guarda `routineId`; `getLastRoutineSession` ignora libres y devuelve la más reciente.
- `settings.test.ts`: get/set/default del toggle.
- `start-workout.test.tsx`: con toggle ON muestra tarjeta Siguiente + "Última…"; con toggle OFF solo "Última…"; sin entrenos con rutina, sin tarjeta.
- Revisar `routine-list.test.tsx` por el cambio de orden alfabético→manual.
- Mantener textos exactos de botones existentes para no romper tests ("Empezar entreno libre", etc.).

## Despliegue

1. DDL aditivo en Neon: `ALTER TABLE workout_sessions ADD COLUMN routine_id text;` y (si aplica) `ALTER TABLE routines ADD COLUMN orden integer;`.
2. `npm test`, `tsc`, `lint`, `next build --webpack`.
3. `vercel --prod`.

## Fuera de alcance (YAGNI)

- No sincronizar el toggle entre dispositivos (es preferencia local).
- No backfill de `routineId` en entrenos antiguos (no hay forma fiable de inferirlo).
- No drag-and-drop para reordenar (flechas ▲▼ bastan, como gimnasios).
- No archivar/desarchivar rutinas en este alcance.
