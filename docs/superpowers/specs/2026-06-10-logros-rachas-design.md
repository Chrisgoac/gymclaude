# Logros y rachas (F) — Diseño

**Fecha:** 2026-06-10
**Estado:** aprobado para plan
**Contexto:** Capa de motivación. Rachas por **objetivos cumplidos** (no días seguidos), hitos desbloqueables y galería de récords, sobre el historial que ya tenemos. Cierra el ciclo con un refuerzo positivo del progreso.

**Decisiones tomadas (brainstorming):**
- MVP incluye las **tres** partes: rachas semanales + hitos/badges + galería de PRs.
- **Rachas por objetivos cumplidos**: semanas ISO consecutivas en las que se cumple el `objetivoSemanal` (sesiones/semana, ya existe en C0). NO por días seguidos.
- **Persistido**: una entidad sincronizada `Achievement` registra el **desbloqueo** de cada hito con su fecha (permite "desbloqueado el X" y evita parpadeos). Rachas y galería de PRs siguen siendo **derivadas** del historial; solo el desbloqueo de hitos se persiste.
- Vive en una **pantalla nueva `/logros`** + un **mini de racha en Home**.

---

## Datos

### Entidad `Achievement` (nueva, sincronizada)
```ts
interface Achievement extends SyncMeta {   // id, updatedAt, deletedAt
  userId: string | null;
  clave: string;            // id del hito del catálogo, p.ej. 'sesiones-50'
  fechaDesbloqueo: number;  // epoch ms cuando se cumplió
}
```
- IDs UUID. **Dexie**: versión **v16**, tabla `achievements: 'id, clave, deletedAt'`.
- **Drizzle/Neon**: tabla `achievements` (`...sync` + `clave text NOT NULL`, `fecha_desbloqueo bigint NOT NULL`). DDL `scripts/migrate-achievements.mjs` (`CREATE TABLE IF NOT EXISTS`).
- **Sync**: 3 registros (collect sin `shouldSync`, apply, server-tables) + backup (**versión 12**). Push genérico (`target: table.id`).
- Repo `lib/repositories/achievements.ts`:
  - `listAchievements()` → activos.
  - `unlockAchievement(clave)` → **idempotente**: si ya existe un activo con esa `clave`, no-op; si no, crea con `fechaDesbloqueo = now`.
  - `getAchievementMap()` → `Map<clave, Achievement>` (para la UI: desbloqueado + fecha).

### Catálogo de hitos (estático, en código) `lib/logros.ts`
```ts
interface LogroMetricas {
  sesionesTotales: number;
  volumenTotal: number;          // suma de peso×reps de todas las series
  prsTotales: number;            // nº de récords actuales (mejor peso por ejercicio entrenado)
  mejorRacha: number;            // mejor racha de semanas cumpliendo objetivo
  mesociclosCompletados: number; // mesociclos cuyo fin (fechaInicio + semanas*7d) ya pasó
}
interface LogroDef {
  clave: string;
  titulo: string;
  descripcion: string;           // criterio legible, p.ej. "50 entrenos"
  criterio: (m: LogroMetricas) => boolean;
}
export const LOGROS_DEF: LogroDef[];
export function evaluarLogros(m: LogroMetricas): string[]; // claves cumplidas
```
Hitos (umbrales ajustables):
- **Sesiones**: `sesiones-10`/`-50`/`-100`/`-250`.
- **Volumen acumulado (kg)**: `volumen-100k`/`-500k`/`-1m`.
- **Racha (semanas)**: `racha-4`/`-8`/`-12`.
- **Mesociclo**: `mesociclo-1` (completar el primero).

## Lógica (derivada del historial)

- **Stats nuevos** (`lib/repositories/stats.ts`, sin tocar datos):
  - `getLogroMetricas(now?)` → `LogroMetricas` (agrega sesiones, volumen total, nº PRs, mesociclos completados, y la mejor racha vía la función de racha).
  - `getRachaSemanal(objetivo, now?)` → `{ actual: number; mejor: number }`. Agrupa sesiones por semana ISO (`inicioSemana`), cuenta cuántas en cada semana, y mide rachas de semanas **consecutivas** con `count >= objetivo`. La "actual" cuenta hacia atrás desde la semana en curso (la semana en curso no rompe la racha aunque aún no llegue al objetivo: si la semana actual va por debajo pero aún no ha terminado, se cuenta la racha hasta la semana previa). Pura sobre los conteos por semana.
  - `listPRs()` → `{ exerciseId; nombre; peso: number; fecha: number }[]` (mejor peso por ejercicio entrenado, con su fecha; para la galería). Reutiliza lo que haya en stats para PRs/series.
- `reconciliarLogros(now?)` (en `lib/logros.ts` o un módulo `lib/reconciliar-logros.ts`): `getLogroMetricas` → `evaluarLogros` → para cada clave cumplida que **no** esté ya en `listAchievements`, llamar `unlockAchievement(clave)`. Idempotente (seguro llamarlo en cada visita). Devuelve las claves recién desbloqueadas (para un posible "¡Nuevo logro!" en la UI, opcional).

Las rachas y la galería de PRs se calculan al vuelo; **solo el desbloqueo de hitos se persiste y sincroniza**.

## UI

- **Pantalla `/logros`** (cliente; acceso desde una tarjeta en Progreso y/o icono en cabecera):
  1. **Racha**: bloque destacado "🔥 Racha actual: N semanas" + "Mejor: M semanas" (objetivo desde `useSetting('objetivoSemanal', 3)`).
  2. **Hitos**: rejilla de badges. Desbloqueados → con su `titulo` + "desbloqueado el dd/mm/yy" (del `Achievement`). Bloqueados → en gris con `descripcion` (criterio). Estética Brutalist.
  3. **Galería de PRs**: lista de récords (`listPRs`) por ejercicio: nombre + mejor peso + fecha.
  - Al montar la pantalla, ejecuta `reconciliarLogros()` para desbloquear lo que toque (vía `useLiveQuery` de `listAchievements` la rejilla se actualiza sola).
- **Mini en Home** (`app/page.tsx`): tarjeta "🔥 Racha: N semanas" que enlaza a `/logros` (patrón de la tarjeta del coach / weekly-digest-mini). También dispara `reconciliarLogros()` (o se deja solo en `/logros` para no recalcular en Home — decisión de implementación: reconciliar solo en `/logros`, el mini solo lee la racha).

## Manejo de errores / casos límite
- **Sin historial** → racha 0, ningún hito, galería vacía; la pantalla invita a entrenar.
- **Reconciliación idempotente**: `unlockAchievement` no duplica; volver a `/logros` no recrea logros.
- **Semana en curso**: no rompe la racha aunque aún no se haya alcanzado el objetivo (se evalúa la racha hasta la semana previa + la actual si ya cumple).
- **Objetivo cambia**: la racha se recalcula con el objetivo vigente (derivada); los hitos de racha ya desbloqueados no se "re-bloquean" (el desbloqueo es permanente).
- **PRs**: empate de peso → la fecha más antigua (primer récord). Ejercicio sin series válidas → no aparece.
- **Volumen total** grande: número entero de kg; se formatea (p.ej. "1.2M kg").

## Estrategia de tests
- **Pura**: `evaluarLogros(metricas)` (umbrales: justo por debajo/encima de cada hito); `getRachaSemanal` sobre conteos por semana mockeados (racha actual con hueco la rompe; semana en curso por debajo no rompe; mejor racha histórica).
- **Repo** `achievements.ts` (fake-indexeddb): `unlockAchievement` crea; segunda llamada con misma clave = no-op; `listAchievements` excluye tombstones; `getAchievementMap`.
- **Stats** (fake-indexeddb): `getLogroMetricas` (siembra sesiones/series/mesociclo → cuenta sesiones, suma volumen, mesociclos completados por fecha); `listPRs` (mejor peso por ejercicio + fecha; empate→más antigua).
- **Reconciliación** (fake-indexeddb): con métricas que cumplen 2 hitos, `reconciliarLogros` crea 2 Achievements; segunda llamada no añade más; devuelve las nuevas.
- **Sync/backup**: `achievements` en collect/apply (convergencia por id) + backup round-trip (versión 12).
- **Componentes**: `/logros` pinta racha + hitos (desbloqueados vs bloqueados) + PRs; mini de Home muestra la racha y enlaza a `/logros`.

## Fases de implementación (F)
- **F1 — Fundación**: entidad `Achievement` sincronizada (Dexie v16 + repo + Drizzle + collect/apply/server-tables + backup v12 + DDL Neon).
- **F2 — Lógica**: `lib/logros.ts` (`LOGROS_DEF` + `evaluarLogros`) + stats (`getLogroMetricas`, `getRachaSemanal`, `listPRs`) + `reconciliarLogros`, con tests.
- **F3 — UI**: pantalla `/logros` (racha + hitos + galería de PRs, dispara reconciliación) + mini de racha en Home + acceso desde Progreso.

## Fuera de alcance
- Notificaciones push de "nuevo logro".
- Hitos sociales / compartir.
- Logros por ejercicio específico o por grupo muscular (solo agregados globales en el MVP).
- Animaciones de desbloqueo (más allá de mostrar el estado).
- Racha por gimnasio (la racha es global; el objetivo semanal es global).
