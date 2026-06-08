# Insights (C) + Ajustes sincronizados — Diseño

**Fecha:** 2026-06-08
**Estado:** aprobado para plan
**Parte de:** roadmap A → C → B. A (motor de progresión) ya está en `main`. Aquí se diseña **C** (insights) más un **módulo 0** de fundación: ajustes sincronizados. B (coach IA) sigue como backlog.

## Problema

La pantalla de Progreso ya muestra volumen semanal, racha, sesiones, mapa muscular (`MuscleBalance`) y progreso/PRs por ejercicio. Pero la app **no detecta estancamientos**, no sugiere deload, no hay un **resumen semanal proactivo** (PRs de la semana, adherencia), y el mapa muscular no señala grupos descuidados ni compara contra metas. Además, **todos los ajustes viven en `localStorage`** y no se sincronizan entre dispositivos, lo cual es incorrecto para preferencias de usuario (modo de progresión, objetivos).

## Decisiones tomadas (vía brainstorming)

- **Estancamiento**: regla = el **mejor 1RM estimado (Epley) no mejora en las últimas N sesiones** (N=3) del mismo gimnasio. Robusta: capta progreso por peso o por reps.
- **Acción al estancarse**: **informativa** — avisar + sugerir deload (~10%) o cambio de ejercicio. **NO** toca el autorrelleno del motor A (desacoplado).
- **Dónde**: badge al entrenar (en la card del ejercicio) **y** lista en Progreso.
- **Resumen semanal**: **proactivo** — mini-tarjeta en Home (Entrenar) + versión completa en Progreso. Métricas: sesiones vs objetivo (adherencia), PRs de la semana, tendencia de volumen vs semana previa.
- **Objetivo semanal de adherencia**: ajuste configurable, default 3 sesiones/semana.
- **Enriquecer existente** (los tres): grupos descuidados en el mapa muscular, comparativa de volumen vs semana previa, y objetivo de volumen por grupo.
- **Ajustes sincronizados (módulo 0)**: nuevo store clave-valor sincronizado. Lo usan los ajustes nuevos de C **y se migran a él los de A** (modo de progresión + incrementos). El toggle "sugerir siguiente rutina" y el filtro de gimnasio **se quedan en `localStorage`** (son por-dispositivo).

---

## Módulo 0 · Ajustes sincronizados (fundación)

### Modelo
- Entidad sincronizable `UserSetting extends SyncMeta { userId: string | null; valor: string }`, donde **`id` = la clave del ajuste** (ej. `id: 'modoProgresion'`), NO un UUID aleatorio.
  - Justificación: los ajustes son por-clave, no objetos independientes. Usar la clave como `id` hace que dos dispositivos del mismo usuario que cambian el mismo ajuste **converjan por LWW** (mismo `id`) en lugar de crear filas duplicadas. Es una desviación consciente del patrón "id UUID global" del resto de entidades.
  - `valor` es un **JSON string** (soporta números, booleanos y objetos como `incrementos` u `objetivosVolumen`).
- **Dexie**: nueva versión con tabla `userSettings: 'id, deletedAt'`.
- **Drizzle/Neon**: tabla `user_settings` con **clave primaria compuesta `(user_id, id)`** (cada usuario tiene una fila por clave) + `valor text`, `updated_at`, `server_updated_at`, `deleted_at`. Migración por **DDL directo** (script `scripts/migrate-*.mjs`, patrón ya usado; `drizzle-kit push` es interactivo y se evita).
- **Sync**: registrar `userSettings`/`user_settings` en los 3 registros (`collect`, `apply`, `server-tables`) y en backup. El upsert del servidor para esta tabla debe resolver el conflicto sobre **`(user_id, id)`**, no sobre `id` solo (a diferencia de las demás tablas). La fusión cliente (LWW por `id`) funciona tal cual porque en el cliente solo hay un usuario y `id` es único.

### API cliente
- Repo `lib/repositories/user-settings.ts`:
  - `getSetting<T>(clave: string): Promise<T | undefined>` — lee de Dexie (activo), parsea `valor`.
  - `setSetting<T>(clave: string, valor: T): Promise<void>` — upsert por `id=clave`, `JSON.stringify(valor)`, `updatedAt = now()`, `deletedAt = null`.
- Hook `useSetting<T>(clave, fallback): [T, (v: T) => void]` en `lib/use-setting.ts`:
  - Lee reactivo con `useLiveQuery` (se actualiza al cambiar local **y al hacer pull**); devuelve `fallback` mientras carga o si no existe.
  - El setter llama `setSetting`.

### Migración de los ajustes de A
- Una vez (en la `upgrade` de la nueva versión de Dexie, o en un init idempotente): si existen en `localStorage` `gymlog.modoProgresion` / `gymlog.incrementos` y aún no hay fila en `userSettings`, sembrar esos valores en `userSettings` (con `updatedAt = now()` para que se sincronicen) y luego dejar de leerlos de `localStorage`.
- `lib/settings.ts`: `useModoProgresion` y `useIncrementos` pasan a apoyarse en `useSetting` (claves `'modoProgresion'`, `'incrementos'`). Mantener `getModoProgresion`/`getIncrementos` no-React solo si algún consumidor no-hook los necesita; si no, migrar todo a `useSetting`. `useSuggestNextRoutine` y `gym-filter` permanecen en `localStorage`.

---

## Módulo 1 · Estancamiento + deload

### Lógica pura
- `lib/insights.ts` → `detectarEstancamiento(points: ExerciseProgressPoint[], n = 3): { estancado: boolean; sesionesSinMejora: number; ultimaMejoraFecha: number | null }`.
  - `points` = `getExerciseProgress(exerciseId, gymId)` (ya existe: por sesión, ordenado por fecha, con `mejor1RM`).
  - Requiere ≥ `n + 1` sesiones; si hay menos → `{ estancado: false, sesionesSinMejora: 0, ultimaMejoraFecha: null }` (datos insuficientes).
  - `mejorReciente` = máx `mejor1RM` de las últimas `n` sesiones; `mejorPrevio` = máx `mejor1RM` de las anteriores.
  - **Estancado** si `mejorReciente <= mejorPrevio`.
  - `sesionesSinMejora` = nº de sesiones desde la última que batió el máximo histórico; `ultimaMejoraFecha` = fecha de esa sesión.
- Constante `DELOAD_CONSEJO = 'Prueba bajar ~10% y vuelve a subir, o cambia de ejercicio.'`

### UI
- **Al entrenar** (`LoggedExerciseCard`): si el ejercicio está estancado, badge `ESTANCADO` + tooltip/línea con `DELOAD_CONSEJO`. Solo informativo; NO altera la sugerencia de A.
- **En Progreso**: sección "Ejercicios estancados" — para cada ejercicio con historial suficiente y `estancado`, una fila con nombre + "sin mejorar desde hace N días" (`formatHaceDias`). Si ninguno, mensaje vacío.
- Helper en `stats.ts` (o `insights.ts`) `listEstancados(gymId?)` que recorre los ejercicios entrenados, calcula `getExerciseProgress` + `detectarEstancamiento`, y devuelve los estancados con su metadata. Filtrado por el gym activo.

---

## Módulo 2 · Resumen semanal (proactivo)

### Lógica pura (`stats.ts`)
- `getWeeklySummary(gymId?): Promise<{ sesiones: number; volumenSemana: number; volumenSemanaPrevia: number; deltaPct: number | null }>` — semana ISO (lunes, reutiliza `inicioSemana`). `deltaPct = null` si no hay semana previa con volumen.
- `getPRsThisWeek(gymId?): Promise<{ exerciseId: string; nombre: string; tipo: 'peso' | '1rm' }[]>` — ejercicios cuyo **máx peso** o **mejor 1RM** de esta semana supera su histórico **anterior** a esta semana.

### Ajuste
- `objetivoSemanal` vía `useSetting('objetivoSemanal', 3)` (sincronizado, módulo 0). Adherencia = `sesiones / objetivoSemanal`.

### UI
- **Home (Entrenar)**: componente `WeeklyDigestMini` — tarjeta compacta `Esta semana · {sesiones}/{objetivo} sesiones · {nPRs} PR · vol {▲/▼ deltaPct%}`, con enlace a Progreso. Si no hay datos de la semana, no se muestra (o muestra "Aún sin entrenos esta semana").
- **Progreso**: componente `WeeklyDigest` arriba — mismas métricas expandidas + lista de PRs de la semana (nombre + tipo).

---

## Módulo 3 · Enriquecer lo existente

### Grupos descuidados
- `stats.ts` → `getLastTrainedByMuscle(gymId?): Promise<Record<MuscleGroup, number | null>>` — timestamp de la última sesión que entrenó cada grupo (null si nunca).
- `MuscleBalance`: junto a cada grupo, "hace N días" (o "nunca"); resaltar (estilo aviso, p. ej. color `destructive`/hazard) los grupos con > **10 días** sin entrenar o nunca.

### Comparativa de volumen vs semana previa
- En el bloque "Volumen semanal" (o en `WeeklyVolumeChart`), mostrar el **% de cambio** de cada semana respecto a la anterior (▲/▼). Cálculo a partir de los `WeeklyVolumePoint` existentes (no requiere nueva consulta).

### Objetivo de volumen por grupo
- Ajuste `objetivosVolumen` vía `useSetting<Partial<Record<MuscleGroup, number>>>('objetivosVolumen', {})` (sincronizado). Solo los grupos que el usuario configure tienen meta.
- `MuscleBalance`: para los grupos con meta, dibujar una **marca de objetivo** sobre la barra y el % vs meta.
- UI de configuración: en **Ajustes**, sección donde el usuario fija meta por grupo (inputs por grupo; vacío = sin meta). Inputs uncontrolled + onBlur (mismo patrón que los incrementos de A) para no atrapar al borrar.

---

## Transversal

- Módulos 1-3 **derivan de datos existentes**: NO tocan el schema de entrenos. El único cambio de datos es el **módulo 0** (`userSettings`/`user_settings`).
- Estética "Brutalist Iron" existente (bordes 2px, radio 0, OKLch, Anton/Archivo/Space Mono, Lucide).
- **Implementación por fases**, cada una su propio plan y desplegable sola:
  - **C0**: ajustes sincronizados (Dexie + Drizzle + migración Neon + sync + backup + `useSetting` + migración de A).
  - **C1**: estancamiento + deload (`insights.ts`, `listEstancados`, badge entreno, sección Progreso).
  - **C2**: resumen semanal (`getWeeklySummary`, `getPRsThisWeek`, `objetivoSemanal`, digest home + Progreso).
  - **C3**: enriquecer (grupos descuidados, comparativa semanal, objetivo de volumen por grupo).
  - Orden recomendado: **C0 → C1 → C2 → C3** (C0 es prerequisito de los ajustes de C2/C3 y de la migración de A).

## Estrategia de tests

- **Puro** (`lib/insights.ts`): `detectarEstancamiento` — insuficientes (<n+1), estancado (sin nuevo máximo en n), mejorando (nuevo máximo reciente), empate exacto cuenta como estancado.
- **Repo/stats** (con fake-indexeddb): `getWeeklySummary` (semana/previa/delta/null), `getPRsThisWeek` (mejora por peso, por 1rm, sin mejora), `getLastTrainedByMuscle` (varios grupos, nunca), `listEstancados` (filtra por gym).
- **user-settings** (fake-indexeddb): `setSetting`/`getSetting` round-trip con número/objeto; `useSetting` fallback; migración desde localStorage idempotente (no pisa si ya existe).
- **Sync**: `userSettings` entra en collect/apply; LWW por `id`-clave converge entre dos "dispositivos" (mismo id, distinto updatedAt → gana el más nuevo); tombstone (reset) se propaga.
- **Componentes**: badge ESTANCADO en la card; `WeeklyDigestMini` (home) y `WeeklyDigest` (Progreso); `MuscleBalance` con "hace N días" + resaltado + marca de meta.

## Casos límite

- **Sin historial / pocas sesiones**: estancamiento → no estancado (datos insuficientes); digest → "aún sin entrenos esta semana".
- **Cambio de gimnasio**: todo respeta el gym activo (las funciones aceptan `gymId?`).
- **`deltaPct` sin semana previa** → `null` (no mostrar flecha).
- **Migración de A**: idempotente; no sobreescribe si ya hay valor sincronizado más nuevo.
- **Convergencia de ajustes**: dos dispositivos editan el mismo ajuste offline → al sincronizar gana el `updatedAt` mayor (LWW), sin duplicar filas (mismo `id`).
- **Reset de ajuste**: borrar (tombstone `deletedAt`) → vuelve al `fallback` del hook.

## Fuera de alcance (backlog)

- **B (coach IA)**: chat/insights con IA sobre las señales ya calculadas por A y C.
- Objetivo de volumen con periodización avanzada; notificaciones push del resumen semanal.
