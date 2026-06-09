# Generador de mesociclos con IA (E) — Diseño

**Fecha:** 2026-06-09
**Estado:** aprobado para plan
**Contexto:** Nueva feature game-changing. El coach (B) hasta ahora aconseja; aquí **planifica**: genera un mesociclo multi-semana completo a partir de un formulario corto + tu historial real, lo revisas y lo guardas como rutinas usables. Cierra el bucle aconsejar → planificar → entrenar → progresar.

**Decisiones tomadas (brainstorming):**
- **Mesociclo multi-semana** (no solo una rutina suelta), modelado **ligero sobre rutinas**: una entidad `Mesocycle` (metadata + esquema de progresión por semana como **guía textual**) + los días del split reusan `Routine`/`RoutineExercise`, etiquetados con `mesocycleId`. **No se materializa una rutina por semana**; la progresión semanal se muestra como guía y se apoya en la autoprogresión existente (A) al entrenar.
- **Parámetros: formulario corto + historial.** Formulario (objetivo, días/semana, semanas, minutos/sesión) + el `CoachSnapshot` (volumen por grupo, estancados, frecuencia) como contexto.
- **Ejercicios: del catálogo + puede proponer nuevos.** La IA elige preferentemente del catálogo existente (se le pasa la lista); si propone uno nuevo, se marca y se crea al guardar (`createExercise`, `esPersonalizado`).
- **Entrega: revisar y guardar.** Nada se crea sin OK del usuario; la pantalla de revisión muestra el plan y marca los ejercicios nuevos.
- **IA: `generateObject` (salida estructurada, no chat)** con DeepSeek ya cableado + **JSON Schema plano** (sin nueva dependencia Zod).

---

## Datos

### Entidad `Mesocycle` (nueva, sincronizada)
```ts
interface Mesocycle extends SyncMeta {   // id, updatedAt, deletedAt
  userId: string | null;
  nombre: string;            // "Hipertrofia 6 semanas"
  objetivo: string;          // 'hipertrofia' | 'fuerza' | 'general' (libre, validado en UI)
  semanas: number;           // total, incluida la descarga
  diasPorSemana: number;
  notas: string | null;      // racional/observaciones del coach
  progresion: SemanaPlan[];  // guía por semana
  fechaInicio: number;       // epoch ms al guardar (ancla de "semana actual")
}

interface SemanaPlan { semana: number; descarga: boolean; ajuste: string }
```
- **Dexie**: versión **v15**, tabla `mesocycles: 'id, fechaInicio, deletedAt'`.
- **Drizzle/Neon**: tabla `mesocycles` (`...sync` + `nombre`/`objetivo` text NN, `semanas`/`dias_por_semana` integer NN, `notas` text nullable, `progresion` **jsonb NN**, `fecha_inicio` bigint NN). `progresion` se guarda como JSON.
- **Sync**: registrar en los 3 registros (collect sin `shouldSync`, apply, server-tables) + backup (**versión 11**). DDL `scripts/migrate-mesocycles.mjs` (`CREATE TABLE IF NOT EXISTS`).

### Cambio en `Routine`
- Añadir campo opcional **`mesocycleId?: string | null`** a `Routine` (y a la tabla Drizzle `routines` vía ALTER idempotente `ADD COLUMN IF NOT EXISTS mesocycle_id text`). Las rutinas-día del mesociclo lo llevan; las rutinas normales lo tienen `null`/ausente.
- Repo: `listStandaloneRoutines()` (rutinas con `mesocycleId` nulo) para la lista normal, y `listRoutinesByMesocycle(mesocycleId)`. La lista de rutinas existente pasa a usar standalone.
- `startSession({ routineId })` no cambia: entrenar un día del mesociclo es entrenar su rutina (que ya referencia el mesociclo vía la rutina).

### Repo `lib/repositories/mesocycles.ts`
- `createMesocycle(input)` → crea la entidad.
- `getMesocycle(id)`, `listMesocycles()` (activos).
- `deleteMesocycle(id)` → tombstone (no borra en cascada las rutinas en el MVP; se documenta).
- `semanaActual(meso, now)` (pura) → número de semana 1..semanas calculado desde `fechaInicio` (`floor((now-fechaInicio)/(7*DIA))+1`, acotado a `[1, semanas]`).

## Mecánica de IA — ruta `/api/coach/mesociclo`

`POST` (espeja `app/api/coach/route.ts`): `auth()` → 401 · `deepseekConfigured()` → 503 · `maxDuration = 120`. Body:
```ts
{
  params: { objetivo: string; diasPorSemana: number; semanas: number; minutosPorSesion: number };
  snapshot: CoachSnapshot;                                   // recogerSnapshot() en el cliente
  catalogo: { nombre: string; grupo: string; equipamiento: string }[];  // listExercises() mapeado
}
```
- Llama **`generateObject({ model: modeloCoach(), schema: MESO_SCHEMA, prompt })`**. El `prompt` (construido por una función pura `promptMesociclo(params, snapshot, catalogo)`) instruye: respeta días/semana y objetivo; usa preferentemente ejercicios del catálogo dado (por nombre); equilibra volumen por grupo teniendo en cuenta estancados/volumen actual; incluye una semana de descarga si procede; progresión semana a semana como `ajuste` corto.
- Devuelve el objeto validado contra el schema y lo responde como JSON. La ruta **no toca la BD** (el guardado es cliente, local-first).

### JSON Schema de la propuesta (`MESO_SCHEMA`)
```jsonc
{
  "type": "object",
  "properties": {
    "nombre": { "type": "string" },
    "objetivo": { "type": "string" },
    "semanas": { "type": "number" },
    "diasPorSemana": { "type": "number" },
    "notas": { "type": "string" },
    "progresion": { "type": "array", "items": { "type": "object", "properties": {
      "semana": { "type": "number" }, "descarga": { "type": "boolean" }, "ajuste": { "type": "string" }
    }, "required": ["semana", "descarga", "ajuste"] } },
    "dias": { "type": "array", "items": { "type": "object", "properties": {
      "nombre": { "type": "string" }, "orden": { "type": "number" },
      "ejercicios": { "type": "array", "items": { "type": "object", "properties": {
        "nombre": { "type": "string" },
        "grupoMuscular": { "type": "string" },
        "equipamiento": { "type": "string" },
        "tipo": { "type": "string" },
        "seriesObjetivo": { "type": "number" },
        "repsObjetivo": { "type": "number" },
        "descansoSegundos": { "type": "number" },
        "nuevo": { "type": "boolean" }
      }, "required": ["nombre","grupoMuscular","equipamiento","tipo","seriesObjetivo","repsObjetivo","descansoSegundos","nuevo"] } }
    }, "required": ["nombre","orden","ejercicios"] } }
  },
  "required": ["nombre","objetivo","semanas","diasPorSemana","progresion","dias"]
}
```

## Flujo (revisar y guardar)

1. **`/rutinas/generar`** (cliente): formulario corto — objetivo (select hipertrofia/fuerza/general), días/semana (2–6), semanas (4–8), minutos/sesión. Botón "Generar". Estado "Generando…"; errores inline (503 sin coach, fallo de red/parseo).
2. **Revisión**: muestra el plan devuelto — cabecera (nombre/objetivo/semanas), tabla de progresión por semana (con marca de descarga), y por día sus ejercicios con targets, **marcando los ejercicios nuevos** (los que se crearán). MVP: revisión de solo lectura + aceptar (editar targets queda fuera del MVP; el usuario puede editar la rutina después con el editor existente).
3. **"Guardar mesociclo"** (`construirMesociclo`, función de guardado en el repo/cliente):
   - `createMesocycle({ nombre, objetivo, semanas, diasPorSemana, notas, progresion, fechaInicio: now })`.
   - Mapeo de ejercicios: para cada ejercicio de cada día, buscar en `listExercises()` por **nombre normalizado** (minúsculas/sin acentos, reusar `slugify` o un normalizador). Si existe → su `id`. Si no → `createExercise({ nombre, grupoMuscular, equipamiento, tipo })` (validando que `grupoMuscular`/`equipamiento`/`tipo` caen en las uniones; fallback `otro`/`otro`/`compuesto` si no).
   - Por cada día: `createRoutine({ nombre })`, fijar su `mesocycleId`, y `addExerciseToRoutine(routineId, { exerciseId, seriesObjetivo, repsObjetivo, descansoSegundos })` en orden.
   - Redirige a `/mesociclo/[id]`.
4. **`/mesociclo/[id]`**: vista del mesociclo — semana actual resaltada (`semanaActual`), su `ajuste`, la lista de progresión, y los días-rutina con botón "Empezar" (`startSession({ routineId })` → `/entrenar/[sessionId]`). Acción de borrar el mesociclo.
5. **Accesos**: la pantalla de rutinas gana un botón "Generar con IA ✨" (→ `/rutinas/generar`) y, si hay mesociclo activo, una tarjeta que enlaza a `/mesociclo/[id]`. La lista de rutinas normal usa `listStandaloneRoutines()` (excluye las del mesociclo).

## Manejo de errores
- Generación: 503 sin DeepSeek → mensaje "Coach no disponible"; fallo de red/timeout o JSON inválido → "No se pudo generar, inténtalo de nuevo" (no se guarda nada).
- Guardado: si el mapeo de un ejercicio falla por datos inválidos, se aplica el fallback de uniones; el guardado es transaccional a nivel de creación local (si algo peta, no se deja un mesociclo a medias — crear primero los ejercicios/rutinas y la entidad Mesocycle al final, o limpiar).
- Catálogo grande: se envía a la IA una lista compacta (nombre/grupo/equipamiento), acotada si fuera necesario.

## Estrategia de tests
- **Pura**: `promptMesociclo(params, snapshot, catalogo)` incluye objetivo/días/semanas y referencia el catálogo + señales del snapshot. `semanaActual(meso, now)` (límites: antes de empezar=1, después del final=semanas, semana intermedia correcta).
- **Repo** `mesocycles.ts` (fake-indexeddb): create/get/list/delete (tombstone); `listStandaloneRoutines` excluye las que tienen `mesocycleId`.
- **Guardado** `construirMesociclo` (fake-indexeddb): crea Mesocycle + N rutinas etiquetadas + RoutineExercises; ejercicios existentes se reutilizan por nombre, los `nuevo` se crean; uniones inválidas → fallback.
- **Ruta** `/api/coach/mesociclo` (node): 401 sin sesión, 503 sin key, 200 devuelve el objeto (mock de `generateObject`/modelo). Mock de `@/lib/coach-model` + `ai`.
- **Sync/backup**: `mesocycles` en collect/apply (convergencia por id) + backup round-trip (versión 11).
- **Componentes**: formulario dispara POST y pinta la revisión; "Guardar" llama al guardado; la vista del mesociclo resalta la semana actual y enlaza "Empezar".

## Casos límite
- **Sin DeepSeek configurado** → la pantalla de generar avisa y deshabilita (como el coach).
- **La IA propone un ejercicio que ya existe con otro nombre** → puede duplicar; se acepta en MVP (normalización por nombre mitiga los casos obvios).
- **`diasPorSemana` no coincide con `dias.length` devueltos** → se respeta lo devuelto por la IA y se informa en la revisión (no bloqueante).
- **Borrar el mesociclo** → tombstone del `Mesocycle`; las rutinas-día quedan (con su `mesocycleId` apuntando a un mesociclo borrado) — en el MVP se documenta; iteración futura: borrado en cascada / archivar.
- **Semana fuera de rango** (mesociclo terminado) → `semanaActual` acota a `semanas` y la UI indica "completado".

## Fases de implementación (E)
- **E1 — Fundación**: entidad `Mesocycle` (Dexie v15 + repo + Drizzle + sync + backup v11 + DDL) + `Routine.mesocycleId` (tipo + Drizzle ALTER + `listStandaloneRoutines`/`listRoutinesByMesocycle`) + `semanaActual` (puro) + ruta `/api/coach/mesociclo` (`generateObject` + `MESO_SCHEMA` + `promptMesociclo` puro). Sin UI.
- **E2 — Generar + revisar + guardar**: `/rutinas/generar` (formulario + llamada + revisión) + `construirMesociclo` (guardado: mesociclo + rutinas + ejercicios con mapeo/creación) + acceso "Generar con IA".
- **E3 — Vista del mesociclo + integración**: `/mesociclo/[id]` (semana actual, progresión, días con "Empezar", borrar) + tarjeta de mesociclo activo + lista de rutinas usando standalone.

## Fuera de alcance
- Progresión **estructurada** que materializa números exactos por semana (se usa guía textual + autoprogresión existente).
- Editar los targets en la pantalla de revisión (se editan después con el editor de rutinas existente).
- Borrado en cascada / archivado de las rutinas al borrar el mesociclo.
- Calendario, recordatorios, o re-generación adaptativa según adherencia.
- Varios mesociclos activos a la vez gestionados como tal (se listan, pero la "semana actual" se calcula por mesociclo).
