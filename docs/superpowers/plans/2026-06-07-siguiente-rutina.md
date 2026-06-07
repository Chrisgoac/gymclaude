# Plan — "Última rutina hecha" + sugerencia de "siguiente rutina"

Spec: `docs/superpowers/specs/2026-06-07-siguiente-rutina-design.md`
Fecha: 2026-06-07
Rama git sugerida: `feat/siguiente-rutina` (PR a `main`)

Build/test recordatorio: este proyecto usa **`next build --webpack`** y **`next dev --webpack`** (Serwist no corre en Turbopack). Tests con vitest (`test.projects`: `api` en node, `app` en jsdom). Email de commits: **chrisgonzaco@gmail.com**.

Convención TDD: por cada paso con lógica, **test primero** → ver fallar → implementar → ver pasar.

---

## Paso 0 — Verificación previa (sin código)

Antes de tocar nada, **leer** para confirmar dos incógnitas del spec:

1. `lib/sync/` (`collect.ts`, `apply.ts`, `server-tables.ts`) y `db/` (`resolveServerWrite`, `db/schema.ts`): ¿el upsert servidor mapea **columna a columna** o guarda el objeto? → determina si `routines.orden` y `workout_sessions.routine_id` **necesitan columna nueva** en Drizzle/Neon. (Casi seguro: columna a columna → sí necesitan columna.)
2. `lib/repositories/backup.ts`: cómo valida el import, para decidir si hace falta bump de `version` y default de `orden`.

Anotar conclusiones; ajustar los pasos 1 y 7 en consecuencia.

---

## Paso 1 — Tipos + esquema (datos)

**Archivos**: `lib/db/types.ts`, `lib/db/database.ts`, `db/schema.ts`.

1. `lib/db/types.ts`:
   - `Routine`: añadir `orden: number;`.
   - `WorkoutSession`: añadir `routineId?: string | null; // null/ausente = entreno libre`.
2. `lib/db/database.ts`: añadir **v9**:
   ```ts
   this.version(9).stores({}).upgrade(async (tx) => {
     const rs = await tx.table('routines').toArray();
     const activas = rs.filter((r) => r.deletedAt === null)
       .sort((a, b) => a.nombre.localeCompare(b.nombre, 'es', { sensitivity: 'base' }));
     for (let i = 0; i < activas.length; i++) {
       await tx.table('routines').update(activas[i].id, { orden: i, updatedAt: Date.now() });
     }
   });
   ```
   (No hace falta reindexar; `orden` no se indexa.)
3. `db/schema.ts` (servidor, **si Paso 0 confirma columna a columna**):
   - `routines`: añadir `orden: integer('orden')`.
   - `workoutSessions`: añadir `routineId: text('routine_id')`.

**Verificación**: `tsc` compila (habrá errores en repos por `orden` faltante → se arreglan en Paso 2).

---

## Paso 2 — Repo de rutinas (orden + reorder)

**Archivo**: `lib/repositories/routines.ts`. **Test**: `lib/repositories/routines.test.ts` (crear o ampliar).

TDD:
1. Test: `createRoutine` asigna `orden` incremental (0, 1, 2…) según rutinas activas existentes.
2. Test: `listRoutines` devuelve ordenado por `orden` asc (desempate `nombre`).
3. Test: `reorderRoutines([id2, id0, id1])` reescribe `orden` = índice y bump `updatedAt`.

Implementación (espejo de `gyms.ts`):
- `createRoutine`: `orden: (activas).length`.
- `listRoutines`: `.sort((a,b) => a.orden - b.orden || a.nombre.localeCompare(...))`.
- `reorderRoutines(idsEnOrden: string[])`: transacción `rw` actualizando `orden:i, updatedAt:ts`.

**Cuidado**: `listRoutines` cambia de alfabético→manual. Revisar y ajustar `components/routine-list.test.tsx` y cualquier test que asuma orden alfabético.

---

## Paso 3 — Función pura de rotación

**Archivo nuevo**: `lib/routine-rotation.ts`. **Test**: `lib/routine-rotation.test.ts`.

```ts
export function getNextRoutineId(
  routinesEnOrden: { id: string }[],
  lastRoutineId: string | null,
): string | null
```
Casos test:
- lista vacía → `null`.
- `lastRoutineId === null` → primer id.
- `lastRoutineId` no presente (borrada) → primer id.
- en medio → siguiente.
- último de la lista → vuelve al primero (ciclo).
- una sola rutina → esa misma.

---

## Paso 4 — Repo de workouts (guardar y leer routineId)

**Archivo**: `lib/repositories/workouts.ts`. **Test**: `lib/repositories/workouts.test.ts`.

TDD:
1. Test: `startSession({ routineId })` persiste `routineId`; `startSession({})` → `routineId` null.
2. Test: `getLastRoutineSession()` devuelve la sesión activa más reciente con `routineId` no nulo, ignorando entrenos libres y borrados; `undefined` si no hay.

Implementación:
- En `startSession`, añadir `routineId: input.routineId ?? null` al objeto `session`.
- `getLastRoutineSession()`: `const ss = await listSessions(); return ss.find((s) => s.routineId != null);` (listSessions ya filtra borrados y ordena fecha desc).

---

## Paso 5 — Setting local (toggle)

**Archivo nuevo**: `lib/settings.ts` (patrón `lib/gym-filter.ts`). **Test**: `lib/settings.test.ts`.

- Clave `gymlog.suggestNextRoutine`, boolean, default `false`.
- `getSuggestNextRoutine()`, `setSuggestNextRoutine(v)`, `useSuggestNextRoutine(): [boolean, (v)=>void]` con `useSyncExternalStore` + evento `settingschange` + `storage`.
- Tests: default false; set→get true; servidor (sin localStorage) → false.

---

## Paso 6 — UI

### 6a. Reordenar rutinas + toggle en Ajustes
**Archivos**: `components/routine-order-manager.tsx` (nuevo), `app/ajustes/page.tsx`. **Test**: `components/routine-order-manager.test.tsx`.

- `RoutineOrderManager`: `useLiveQuery(listRoutines)`, lista `brutal-box divide-y-2`, cada fila con nombre + flechas ▲▼ que reconstruyen el array y llaman `reorderRoutines`. ▲ deshabilitada en el primero, ▼ en el último.
- En `app/ajustes/page.tsx`: añadir sección **"Rutinas"** con `RoutineOrderManager`, y un **toggle** "Sugerir siguiente rutina (rotación)" (`useSuggestNextRoutine`). Estilo brutalista (label-mono + control cuadrado).

### 6b. Tarjeta "Siguiente" + "Última" en Entrenar
**Archivo**: `components/start-workout.tsx`. **Test**: `components/start-workout.test.tsx`.

- Añadir: `const last = useLiveQuery(() => getLastRoutineSession(), [])`, `const [suggest] = useSuggestNextRoutine()`.
- `nextId = getNextRoutineId(routines ?? [], last?.routineId ?? null)`; resolver nombre desde `routines`.
- Helper `formatHaceDias(ts)` (local o reusar de Historial): "hoy" / "ayer" / "hace N días".
- Render arriba (antes del botón libre):
  - **suggest === true && nextRoutine**: `brutal-box` destacada: "SIGUIENTE" (label-mono), nombre grande, `<Button>Empezar {nombre}</Button>` → `setPendiente({tipo:'rutina', routineId: nextId})`. Debajo línea pequeña "Última: {nombreÚltima} · {hace N días}" (si hay última).
  - **suggest === false**: solo línea pequeña "Última: {nombre} · hace N días" si hay última; si no, nada.
- No tocar el flujo `GymPicker`/`empezarConGym` ni la lista "Desde una rutina".

Tests `start-workout`:
- toggle ON + última rutina existente → aparece tarjeta "SIGUIENTE" con la rutina correcta y "Última…".
- toggle OFF → no tarjeta; aparece "Última…".
- sin ninguna sesión con rutina → ni tarjeta ni "Última…".
- Mantener texto exacto "Empezar entreno libre".

---

## Paso 7 — Backup (si Paso 0 lo exige)

**Archivo**: `lib/repositories/backup.ts`.
- Asegurar que import de rutinas sin `orden` (copias antiguas) no rompe → default `orden: 0` (o re-secuenciar). Bump `version` 6→7 solo si la validación es estricta.
- Test correspondiente en `backup.test.ts` si se toca.

---

## Paso 8 — Verificación final + despliegue

1. `npm test` (todos verdes; revisar contadores — partimos de ~111).
2. `npx tsc --noEmit`, `npm run lint`.
3. `npm run build` (usa `--webpack`).
4. **Neon DDL aditivo** (antes del deploy de código que lo usa):
   - `ALTER TABLE workout_sessions ADD COLUMN routine_id text;`
   - `ALTER TABLE routines ADD COLUMN orden integer;` (si Paso 0 lo confirma)
   (Usar script `.mjs` con la conexión Neon, como en migraciones previas — NO `drizzle-kit push` interactivo.)
5. Commit(s) verdes, push a GitHub, `vercel --prod`.
6. Verificar en vivo: home 200, empezar entreno desde rutina graba `routineId`, tarjeta Siguiente aparece tras 1 entreno, toggle en Ajustes funciona.

---

## Resumen de archivos

**Nuevos**: `lib/routine-rotation.ts` (+test), `lib/settings.ts` (+test), `components/routine-order-manager.tsx` (+test).
**Modificados**: `lib/db/types.ts`, `lib/db/database.ts` (v9), `db/schema.ts`, `lib/repositories/routines.ts` (+test), `lib/repositories/workouts.ts` (+test), `app/ajustes/page.tsx`, `components/start-workout.tsx` (+test), posiblemente `lib/repositories/backup.ts`, `components/routine-list.test.tsx` (ajuste de orden).

## Riesgos / notas
- Cambio de orden de `listRoutines` (alfabético→manual) afecta la lista de Entrenar y de Rutinas; revisar tests y que la UX siga clara.
- `routineId` solo se graba desde ahora: la sugerencia "despega" tras el primer entreno desde una rutina (documentar/esperado).
- Si Paso 0 revela que sync NO mapea columna a columna, omitir los `ALTER TABLE`/columnas Drizzle.
