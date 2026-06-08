# Sugerencia de peso + repes/descanso visibles al entrenar — Diseño

**Fecha:** 2026-06-08
**Estado:** aprobado para plan

## Problema

Al entrenar, la app ya autorrellena el peso de la última serie (mismo gimnasio) de
forma **silenciosa** dentro de `añadirSerie`, pero el usuario no ve esa sugerencia ni
ninguna referencia de qué hizo la última vez o qué prescribe la rutina. Además no hay
ninguna ayuda para el descanso entre series.

El usuario quiere, en cada ejercicio mientras entrena:

1. **Sugerencia de peso visible** basada en un entreno anterior, **solo si el gimnasio
   coincide** (su frase: "si has hecho ese ejercicio en algún entreno anteriormente y el
   gimnasio coincide, que te sugiera el peso").
2. **Ver las repes y el descanso** ("el tiempo" = tiempo de descanso) en cada ejercicio.
3. Para el descanso: **mostrar el objetivo de la rutina Y un cronómetro** que arranca tras
   cada serie.

## Decisiones tomadas (vía AskUserQuestion)

- "El tiempo" = **tiempo de descanso** entre series.
- Descanso = **ambas cosas**: mostrar el objetivo de la rutina + cronómetro.
- Origen de los números = **ambos, en dos líneas**: una de historial ("última vez") y otra
  de objetivo de rutina.
- El cronómetro **arranca automáticamente al pulsar "Añadir serie"** (la opción más simple,
  sin botón dedicado de inicio).

## Alcance

Todo se **deriva de datos que ya existen** (historial de series + objetivos de la rutina).
**No** se toca el esquema Dexie, ni Drizzle, ni el motor de sync, ni el backup. Estado del
cronómetro **solo en cliente** (no se persiste ni se sincroniza).

## Diseño

### 1. Bloque de referencia en `LoggedExerciseCard`

Bajo la cabecera del ejercicio, dos líneas mono compactas (estilo `label-mono`,
Brutalist Iron). Cada línea aparece **solo si hay datos**:

- **Última vez** (historial, mismo gimnasio):
  `ÚLTIMA VEZ · 80 × 10 · hace 4 días`
  - Peso, reps y "hace N días" del último set registrado de ese ejercicio en **este
    gimnasio** (misma lógica de filtrado por gym que `getLastSet`). Si el entreno no tiene
    gimnasio (`gymId` ausente), se comporta como hoy: última serie sin filtrar por gym.
  - Si no hay registro previo (en ese gym), **no se muestra la línea**.
- **Objetivo** (de la rutina del entreno):
  `OBJETIVO · 4 × 10 · desc. 90s`
  - `seriesObjetivo × repsObjetivo` y `descansoSegundos`, leídos del `RoutineExercise`
    correspondiente a `(routineId, exerciseId)`.
  - Solo aparece si el entreno viene de una rutina (`session.routineId != null`) y existe ese
    `RoutineExercise`. Cada subdato (series/reps/descanso) se omite si está vacío.
  - En entreno libre no hay objetivo → no se muestra esta línea.

Si ninguna de las dos líneas tiene datos, el bloque no ocupa espacio.

### 2. Cronómetro de descanso — componente nuevo `components/rest-timer.tsx`

Client component autónomo, estado local con `useState` + `useEffect` (intervalo de 1s).

- **Props:** `targetSeconds?: number` (= `descansoSegundos` de la rutina) y un trigger de
  arranque (ver más abajo).
- **Comportamiento:**
  - Si hay `targetSeconds`: **cuenta atrás** desde ese valor; muestra `mm:ss` restante.
  - Si no hay `targetSeconds`: **cuenta hacia arriba** como cronómetro libre desde 0.
  - Al llegar a 0 (cuenta atrás): parpadeo visual + `navigator.vibrate(...)` si está
    disponible (guard de feature-detection; no rompe en escritorio/SSR).
  - Control manual mínimo: tocar el cronómetro lo **para/reinicia** (sin botón de inicio
    dedicado, según decisión).
- **Arranque:** se dispara desde `LoggedExerciseCard` cuando el usuario pulsa "Añadir
  serie". Implementación: la card mantiene un contador/clave de arranque que incrementa en
  `añadirSerie`; el `RestTimer` reacciona a ese cambio (vía prop key o efecto) y (re)inicia.
- **Formato `mm:ss`:** helper puro nuevo `formatSegundos(s: number): string` en
  `lib/fecha.ts` (testeable de forma aislada).

### 3. Plomería (capa de repositorios)

**`lib/repositories/workouts.ts` — `getLastPerformance`:**

```ts
export async function getLastPerformance(
  exerciseId: string,
  excludeSessionId?: string,
  gymId?: string | null,
): Promise<{ peso: number; reps: number; fecha: number } | undefined>
```

Reutiliza la lógica de búsqueda de candidatos de `getLastSet` (mismo filtrado por gym y
ordenación por fecha desc), pero devuelve también la **fecha** de la sesión para el
"hace N días". Para evitar duplicar lógica, se extrae la búsqueda del último set + su
sesión a un helper interno compartido por `getLastSet` y `getLastPerformance`
(`getLastSet` se mantiene con su firma actual para no romper a sus consumidores).

**`lib/repositories/routines.ts` — `getRoutineExerciseTarget`:**

```ts
export async function getRoutineExerciseTarget(
  routineId: string,
  exerciseId: string,
): Promise<RoutineExercise | undefined>
```

Filtra `listRoutineExercises(routineId)` por `exerciseId` (primer activo). Devuelve
`undefined` si no hay rutina o no está el ejercicio.

### 4. Cableado de la página

`app/entrenar/[sessionId]/page.tsx` pasa `routineId={session.routineId ?? undefined}` a
`LoggedExerciseCard` (ya pasa `gymId`). La card hace dos `useLiveQuery` nuevos
(`getLastPerformance`, `getRoutineExerciseTarget`) y renderiza el bloque + el cronómetro.

## Componentes y responsabilidades

| Unidad | Qué hace | Depende de |
|---|---|---|
| `getLastPerformance` | Peso/reps/fecha del último set del ejercicio (mismo gym) | Dexie (loggedExercises/Sets, sessions) |
| `getRoutineExerciseTarget` | Objetivos de la rutina para un ejercicio | `listRoutineExercises` |
| `formatSegundos` | `123` → `2:03` | — (puro) |
| `RestTimer` | Cuenta atrás/arriba + aviso a 0 | `formatSegundos`, `navigator.vibrate` |
| `LoggedExerciseCard` | Orquesta bloque de referencia + cronómetro | lo anterior |

## Manejo de errores / casos límite

- Sin historial en ese gym → línea "Última vez" oculta.
- Entreno libre (sin rutina) → línea "Objetivo" y cuenta atrás con target ausentes;
  cronómetro funciona en modo "cuenta arriba".
- `descansoSegundos` = 0 o ausente → cuenta arriba.
- `navigator.vibrate` inexistente (escritorio/SSR) → se omite la vibración.
- Datos parciales del objetivo (p. ej. solo reps) → se muestran solo los subdatos presentes.

## Pruebas

- `getLastPerformance`: devuelve el último set correcto filtrando por gym; `undefined` sin
  datos; incluye la fecha de la sesión; excluye la sesión actual.
- `getRoutineExerciseTarget`: encuentra el ejercicio; `undefined` si no está / sin rutina.
- `formatSegundos`: `0→0:00`, `5→0:05`, `65→1:05`, `600→10:00`.
- `RestTimer` / `LoggedExerciseCard`: smoke test de render con/sin datos (jsdom), sin
  depender de timers reales salvo lo mínimo. Se respetan los textos existentes
  ("Añadir serie", "Peso", "Reps") para no romper tests actuales.

## Fuera de alcance (YAGNI)

- Persistir/sincronizar el estado del cronómetro.
- Sonido (solo vibración + visual).
- Editar objetivos de la rutina desde la pantalla de entreno.
- Histórico de descansos reales por serie (no se registra el descanso, solo se cronometra).
