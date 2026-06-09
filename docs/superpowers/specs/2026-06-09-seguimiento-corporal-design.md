# Seguimiento corporal (D) — Diseño

**Fecha:** 2026-06-09
**Estado:** aprobado para plan (D1)
**Nuevo pilar:** la app mide lo que levantas (rutinas, logging, progresión, insights, coach) pero no a *ti*. D añade el seguimiento del cuerpo: peso corporal, medidas y fotos de progreso. Cierra el bucle planificar → entrenar → progreso → **cuerpo**, y alimenta al coach (B).

Se divide en **dos sub-features independientes**, cada una su spec→plan→implementación:
- **D1 — Peso + medidas** (series numéricas). Se diseña aquí a fondo.
- **D2 — Fotos de progreso** (reusa R2). Documentada al final; su propio ciclo después.

---

## D1 — Peso corporal + medidas

### Decisiones tomadas (vía brainstorming)
- **Modelo: entidad única tipada** `BodyMetric { tipo, valor, fecha }`. Peso y cada medida son entradas tipadas; cada `tipo` tiene su serie. Extensible sin tocar schema.
- **Métricas: predefinidas + personalizadas.** Set predefinido (peso + medidas comunes) y, además, métricas propias (nombre + unidad) que el usuario crea.
- **Personalizadas en el store de ajustes sincronizado (C0)**, clave `metricasPersonalizadas` — sin entidad nueva para las definiciones.
- **Ubicación: pantalla `/cuerpo`** alcanzable desde una tarjeta en Progreso (no se añade pestaña a la navbar).
- **Coach: peso + medidas clave** en el snapshot (último valor + tendencia ~4 semanas).
- Unidades: peso en **kg**, medidas en **cm** (implícitas por el tipo, vía `resolverMetrica`). Consistente con la app (kg).

### Datos — entidad `BodyMetric`
```ts
interface BodyMetric extends SyncMeta {
  userId: string | null;
  tipo: string;   // clave predefinida ('peso','cintura',...) o personalizada
  valor: number;  // kg para peso, cm para medidas, o la unidad de la personalizada
  fecha: number;  // epoch ms (cuándo se midió); ordena la serie
}
```
- IDs UUID (patrón estándar, como `coach_messages`/`gyms`).
- **Dexie**: nueva versión con tabla `bodyMetrics: 'id, tipo, fecha, deletedAt'`.
- **Drizzle/Neon**: tabla `body_metrics` (`...sync` estándar + `tipo text NOT NULL`, `valor double precision NOT NULL`, `fecha bigint NOT NULL`). Migración DDL directa (patrón `scripts/migrate-*.mjs`).
- **Sync**: registrar `bodyMetrics`/`body_metrics` en los 3 registros (collect sin shouldSync, apply, server-tables) + backup (subir versión). Push genérico (`target: table.id`) sirve (id PK estándar).
- Repo `lib/repositories/body.ts`:
  - `addMetric(tipo, valor, fecha = Date.now())` → crea BodyMetric.
  - `listMetrics(tipo)` → entradas activas de ese tipo, orden cronológico asc.
  - `listTipos()` → tipos que tienen al menos una entrada activa (para saber qué graficar).
  - `deleteMetric(id)` → tombstone.

### Registro de métricas (predefinidas + personalizadas)
- `lib/body-metrics.ts`: `METRICAS_PREDEF: Record<string, { label: string; unidad: string }>` con `peso` (kg) + `cintura`, `cadera`, `pecho`, `hombros`, `biceps`, `antebrazo`, `muslo`, `pantorrilla`, `cuello` (cm). Orden definido para la UI.
- Personalizadas: ajuste sincronizado `metricasPersonalizadas` (C0 `useSetting`/`getSetting`), `Array<{ clave: string; label: string; unidad: string }>`. CRUD mínimo (añadir; clave = slug del nombre).
- `resolverMetrica(tipo, personalizadas): { label, unidad }` (puro) — busca en predefinidas, luego en personalizadas, y si no existe devuelve `{ label: tipo, unidad: '' }` (defensivo).

### Pantalla `/cuerpo`
- **Registrar**: selector de métrica (predefinidas + personalizadas), campo valor (numérico, ≥0; `parseDecimal`), fecha (hoy por defecto, editable) → `addMetric`. Botón "Gestionar métricas" abre un mini-form para crear una personalizada (nombre + unidad) que persiste en `metricasPersonalizadas`.
- **Por métrica con datos** (`listTipos`): tarjeta con gráfica de tendencia (Recharts `LineChart`, mismo estilo que `components/exercise-chart.tsx`), valor actual + cambio respecto al primero del periodo visible, y lista de entradas con opción de borrar (tombstone).
- Estética Brutalist Iron; unidades vía `resolverMetrica`.
- Acceso: tarjeta "Seguimiento corporal →" en `app/progreso/page.tsx` que enlaza a `/cuerpo` (patrón de la tarjeta del coach en Home).

### Integración con el coach (snapshot B2)
- `lib/coach-snapshot.ts`:
  - `construirSnapshot` añade al `CoachSnapshot` un campo `cuerpo: { peso: { actual: number; delta4sem: number | null } | null; medidas: { metrica: string; actual: number; delta4sem: number | null }[] }`.
  - `recogerSnapshot` consulta `listMetrics` por tipo, toma el último valor y calcula el cambio vs el valor de hace ~4 semanas (o el más antiguo dentro de la ventana). "Medidas clave" = las que tengan datos (top-N por recencia, acotado).
- El system prompt ya serializa el snapshot como JSON → el coach podrá razonar sobre recomposición (peso sube, cintura baja, etc.). No cambia el prompt.

### Estrategia de tests
- **`resolverMetrica`** (puro): predefinida, personalizada, desconocida → fallback.
- **Repo `body.ts`** (fake-indexeddb): add/list orden cronológico, listTipos (solo con datos), delete = tombstone.
- **Sync**: collect/apply incluyen `bodyMetrics` (convergencia por id); backup round-trip.
- **Snapshot**: `construirSnapshot` con datos corporales mock (peso + medidas → actual + delta4sem); sin datos → `cuerpo` vacío/null. `recogerSnapshot` integración (siembra BodyMetric, refleja peso/medidas).
- **Componentes**: registrar añade una métrica; gráfica renderiza una serie; crear personalizada la deja disponible en el selector.

### Casos límite
- **Sin datos** → la pantalla invita a registrar; el coach recibe `cuerpo` vacío y no inventa.
- **Una sola entrada de un tipo** → gráfica con un punto; `delta4sem = null` (sin referencia previa).
- **Métrica personalizada borrada de los ajustes** pero con entradas BodyMetric: `resolverMetrica` cae al fallback (`label = tipo`), no rompe.
- **Valores**: no negativos; decimales permitidos (peso 78,4; cintura 84,5).
- **Fecha futura**: se permite pero se desaconseja en UI (no bloqueante).

### Fases de implementación (D1)
- **D1a**: entidad `BodyMetric` sincronizada (Dexie + repo + Drizzle + collect/apply/server-tables + backup + DDL Neon).
- **D1b**: registro de métricas — `METRICAS_PREDEF` + `resolverMetrica` (puro) + `metricasPersonalizadas` (useSetting) + CRUD mínimo.
- **D1c**: pantalla `/cuerpo` (registrar + gráficas por métrica + gestionar personalizadas) + tarjeta en Progreso.
- **D1d**: integración con el snapshot del coach (peso + medidas clave + delta 4 semanas).

---

## D2 — Fotos de progreso (sub-feature posterior, no en D1)

- Entidad sincronizada `ProgressPhoto extends SyncMeta { userId, url, key, fecha, notas? }` (foto del cuerpo con fecha; distinta de `ExercisePhoto`).
- Reusa **R2** (`lib/r2/client.ts`: `putImage`/`deleteR2Object`/`publicUrl`/`r2Configured`), `compressImage` (`lib/image/compress.ts`) y el patrón de ruta `app/api/exercise-photos/route.ts` (Clerk 401, 503 sin R2, 403 anti-IDOR por key namespaced `${userId}/progress/${uuid}.jpg`, 413 >8MB). Probablemente una ruta nueva `/api/progress-photos` o ampliar la existente con un "tipo".
- UI en `/cuerpo`: galería por fecha + **comparar dos** (antes/después). Sync + backup como las demás entidades; migración Neon `progress_photos`.
- Decisiones de diseño propias (¿comparar lado a lado o slider?, ¿privacidad?) se brainstormean en su momento. Fuera del alcance de D1.

## Fuera de alcance (ambas)
- Báscula/wearable/Apple Health/Google Fit (importación automática).
- Objetivos de peso/medidas con predicción.
- Cálculo de % graso por fórmulas de pliegues.
