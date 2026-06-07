# Mejora visual móvil + Progreso con charts — Diseño

**Fecha:** 2026-06-07
**Estado:** Aprobado, listo para plan de implementación

## Objetivo

Dos mejoras de UI en la app GymLog (móvil-first, estética "Brutalist Iron"):

1. **Navbar inferior** — hoy mete 6 pestañas solo-texto en ancho de móvil; se ve apretado e ilegible.
2. **Página de Progreso** — hoy es un desplegable + una línea de 1RM + una lista de texto de volumen. Poco visual y cuesta sacar conclusiones.

Restricción transversal: respetar el sistema de diseño existente (Tailwind v4 con tokens OKLch en `globals.css`, bordes 2px, radio 0, sombras duras, fuentes Anton/Archivo/Space Mono, iconos Lucide). No tocar datos, sync ni el schema de Dexie.

## Parte 1 — Navbar inferior (Opción A: iconos + 5 pestañas)

**Decisión:** reducir de 6 a 5 pestañas y añadir iconos. "Ajustes" sale de la barra.

### `components/bottom-nav.tsx`
- Pasar de `grid-cols-6` a `grid-cols-5`.
- Pestañas: Entrenar (`/`), Rutinas (`/rutinas`), Ejercicios (`/ejercicios`), Progreso (`/progreso`), Historial (`/historial`).
- Cada pestaña: **icono Lucide + etiqueta** apilados verticalmente (icono ~18-20px arriba, etiqueta `label-mono` debajo).
- Iconos sugeridos (Lucide React, ya instalado): `Dumbbell` (Entrenar), `ListChecks` o `ClipboardList` (Rutinas), `LayoutGrid` (Ejercicios), `TrendingUp` o `LineChart` (Progreso), `History` (Historial).
- Mantener estado activo: fondo `bg-primary text-primary-foreground`, barra superior naranja/ink (`absolute inset-x-0 top-0 h-1 bg-foreground`), separadores verticales 2px entre tabs.
- Conservar la lógica de "activo": la pestaña Entrenar (`/`) sigue cubriendo `/entrenar/...`.

### Pulido móvil
- Añadir `pb-[env(safe-area-inset-bottom)]` (o equivalente) a la barra para móviles con notch/home-indicator.
- Ajustar el `pb-24` del `<body>` en `app/layout.tsx` si la nueva altura del navbar lo requiere.
- Áreas de toque cómodas (mínimo ~44px de alto efectivo).

### Ajustes → cabecera
- Añadir un icono ⚙ (`Settings` de Lucide) en `components/auth-header.tsx`, enlazando con `Link` a `/ajustes`.
- Marcar estado activo cuando `pathname` empiece por `/ajustes` (mismo criterio visual ligero que el resto de la cabecera).

### Tests
- Actualizar `components/bottom-nav.test.tsx`: ahora 5 pestañas (no 6), "Ajustes" ya no está en la barra, las etiquetas siguen presentes y los `href` son correctos.

## Parte 2 — Página de Progreso (5 módulos)

Reescritura de `app/progreso/page.tsx` componiendo, de arriba a abajo:

### Módulo 1 — Selector de periodo
- Segmented control con 4 opciones: **4 sem / 3 meses / Año / Todo**.
- Componente nuevo `components/period-selector.tsx` (presentacional, controlado por la página).
- La página mantiene estado local `periodo` (igual que ya hace con el filtro de gimnasio vía `useGymFilter`).
- Helper `periodoASinceTs(periodo)` que devuelve el timestamp `sinceTs` de inicio (`0` para "Todo"). Ubicación: junto al resto de utilidades de progreso (p. ej. `lib/period.ts` o dentro de `stats.ts`).
- `sinceTs` alimenta a los módulos 2, 3 y 5. La **racha** (módulo 2) es siempre "actual" y NO depende del periodo.

### Módulo 2 — Resumen (stat-cards)
- Fila de 3 tarjetas (`grid-cols-3`): **Racha** (🔥, `getCurrentStreakDays()`), **Sesiones** (del periodo), **Volumen total** (del periodo, en kg o toneladas si es grande).
- Estilo: borde 2px, sombra dura (`box-shadow: 3px 3px 0 0 var(--color-foreground)`), número con `.stat`/fuente display, unidad con `label-mono`.
- Función nueva en `stats.ts`: `getPeriodSummary(sinceTs, gymId)` → `{ sesiones: number; volumen: number }`. Puede derivarse filtrando `listSessionSummaries` por `fecha >= sinceTs`, o consulta dedicada.

### Módulo 3 — Volumen semanal (barras)
- `BarChart` de Recharts (ya instalado) con volumen agregado por semana dentro del periodo.
- Componente nuevo `components/weekly-volume-chart.tsx`.
- Función nueva en `stats.ts`: `getWeeklyVolume(sinceTs, gymId)` → `{ semanaInicioTs: number; volumen: number }[]`, agrupando sesiones por semana (semana ISO empezando lunes). Etiqueta del eje X: día/mes del inicio de semana.
- Estilo brutalista: barras con relleno `--color-primary`, borde 2px ink, ejes `currentColor`, tooltip mono (mismo patrón que `ExerciseChart`).

### Módulo 4 — Por ejercicio (mejorado)
- Mantener el desplegable de ejercicio existente.
- Añadir 2 cards de **PR**: máx peso y 1RM est. (`getExercisePRs`, ya existe).
- Añadir **toggle de métrica** (segmented): **1RM / Peso máx / Volumen**. Los 3 valores ya están en `ExerciseProgressPoint` (`mejor1RM`, `maxPeso`, `volumen`); el chart dibuja la métrica seleccionada.
- Modificar `components/exercise-progress.tsx` y `components/exercise-chart.tsx`: `ExerciseChart` recibe una prop `metric: '1rm' | 'peso' | 'volumen'` y dibuja la serie correspondiente (con su etiqueta).
- Aplicar el filtro de periodo: añadir parámetro opcional `sinceTs` a `getExerciseProgress(exerciseId, gymId, sinceTs?)` (filtra puntos por `fecha >= sinceTs`).

### Módulo 5 — Balance muscular (barras horizontales)
- Sustituir la lista de texto actual por **barras horizontales**: etiqueta del grupo + barra proporcional (relleno naranja, track `--color-card` con borde 2px) + valor.
- Componente nuevo `components/muscle-balance.tsx`.
- Datos: `getVolumeByMuscle(sinceTs, gymId)` (ya existe y ya devuelve ordenado desc). Pasar `sinceTs` del selector de periodo (la función ya acepta `sinceTs`).
- Etiquetas vía `muscleGroupLabel` (ya existe). Barra normalizada al máximo de la lista.

## Capa de datos — resumen de cambios en `lib/repositories/stats.ts`

- **Nueva** `getWeeklyVolume(sinceTs, gymId)`.
- **Nueva** `getPeriodSummary(sinceTs, gymId)` → `{ sesiones, volumen }`.
- **Modificar** `getExerciseProgress` para aceptar `sinceTs?` opcional (sin romper llamadas actuales).
- Reutilizar sin cambios: `getCurrentStreakDays`, `getExercisePRs`, `getVolumeByMuscle` (ya acepta `sinceTs`), `muscleGroupLabel`.

Todas las funciones nuevas siguen el patrón existente (filtran `deletedAt === null` vía `activo()`, respetan `gymId`).

## Componentes nuevos / modificados

| Archivo | Acción |
|---|---|
| `components/bottom-nav.tsx` | Modificar: 5 tabs + iconos |
| `components/bottom-nav.test.tsx` | Modificar: refleja 5 tabs |
| `components/auth-header.tsx` | Modificar: icono ⚙ → /ajustes |
| `app/layout.tsx` | Modificar (si hace falta): padding inferior |
| `app/progreso/page.tsx` | Reescribir: compone los 5 módulos |
| `components/period-selector.tsx` | Nuevo |
| `components/weekly-volume-chart.tsx` | Nuevo |
| `components/muscle-balance.tsx` | Nuevo |
| `components/stat-card.tsx` | Nuevo (o estilos inline reutilizando tokens) |
| `components/exercise-progress.tsx` | Modificar: PRs + toggle métrica |
| `components/exercise-chart.tsx` | Modificar: prop `metric` |
| `lib/repositories/stats.ts` | Añadir `getWeeklyVolume`, `getPeriodSummary`, `sinceTs` en `getExerciseProgress` |
| `lib/period.ts` | Nuevo (o helper en stats): `periodoASinceTs` |

## Fuera de alcance (YAGNI)

- No tocar datos, sync con servidor, Drizzle ni el schema de Dexie.
- Sin nuevas librerías (Recharts y Lucide ya están).
- Sin métricas avanzadas (PR por rango de reps, comparativas entre periodos, exportación). Si surgen, irán en su propio ciclo.

## Verificación

- `bottom-nav.test.tsx` pasa con la nueva estructura.
- Build/typecheck limpio.
- Comprobación manual en viewport móvil: navbar legible y sin desbordes; los 5 módulos de progreso renderizan con datos reales y responden al selector de periodo y al filtro de gimnasio.
