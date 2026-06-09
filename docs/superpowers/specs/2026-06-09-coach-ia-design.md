# Coach IA (B) — Diseño

**Fecha:** 2026-06-09
**Estado:** aprobado para plan
**Parte de:** roadmap A → C → B. A y C ya están en producción. B es la última fase: un coach IA que razona sobre las señales calculadas por A (progresión) y C (estancamiento, resumen semanal, volumen por grupo).

## Problema

La app ya calcula señales ricas (estancamiento, PRs de la semana, adherencia, volumen por grupo, grupos descuidados, sugerencia de peso) pero el usuario no puede *preguntar* sobre ellas ni recibir consejo accionable ("¿subo de peso en sentadilla?", "llevo 3 semanas estancado, ¿qué hago?", "¿qué ejercicio sustituyo?"). B añade un coach conversacional + insights rápidos que interpreta esas señales con un LLM.

## Decisiones tomadas (vía brainstorming)

- **Interacción:** chat conversacional **+ insights rápidos** (chips que pre-rellenan y envían una pregunta).
- **Datos al modelo:** la app es **local-first**, así que el cliente arma un **snapshot compacto** de las señales (calculadas en Dexie) y lo envía a la ruta; el servidor NO recalcula stats. (Tool-calling descartado: los datos viven en el cliente.)
- **Proveedor:** **DeepSeek directo con su API Key** (`DEEPSEEK_API_KEY`, solo servidor) vía el AI SDK de Vercel — NO el AI Gateway (decisión explícita del usuario). Modelo rápido/barato de DeepSeek (V3 chat) por defecto, en una constante fácil de cambiar.
- **Persistencia:** chat **guardado y sincronizado** entre dispositivos — entidad nueva `CoachMessage` por el sync. **Un único hilo continuo** (sin gestión multi-conversación).
- **Acceso:** icono en la **cabecera** (`auth-header`) + **tarjeta en Home** (Entrenar). NO se añade pestaña a la navbar (ya tiene 5).

## Arquitectura y flujo

Local-first con servidor como **proxy autenticado** (no guarda ni recalcula nada):

1. Pantalla `/coach` (cliente): lee el hilo sincronizado desde Dexie (`useLiveQuery`) y arma el **snapshot** con `construirSnapshot()`.
2. Al enviar un mensaje: escribe el mensaje del usuario en Dexie (se sincroniza) y llama a `POST /api/coach` con `{ mensajes, snapshot }`.
3. `POST /api/coach` (Clerk `auth()` → 401 sin sesión): construye el system prompt (persona de coach + snapshot serializado + disclaimer) y llama a **DeepSeek** con el AI SDK (`streamText`), devolviendo la respuesta en **streaming**.
4. El cliente muestra el stream; al completarse, escribe el mensaje del asistente en Dexie (se sincroniza).
5. El sync propaga ambos mensajes a Neon y a otros dispositivos.

**Nota Next/AI SDK:** seguir `AGENTS.md` — leer la guía vigente del AI SDK (v6) en `node_modules` antes de codificar la ruta y el hook de chat. La integración DeepSeek usa el provider DeepSeek del AI SDK (compatible OpenAI); el slug exacto del modelo se confirma al implementar y vive en una constante.

## Datos — entidad `CoachMessage`

```ts
interface CoachMessage extends SyncMeta {
  userId: string | null;
  rol: 'user' | 'assistant';
  contenido: string;
  createdAt: number; // epoch ms; orden del hilo
}
```
- Un único hilo (sin `threadId`). Se ordena por `createdAt`.
- IDs UUID generados en cliente (patrón estándar, NO clave-valor como `UserSetting`).
- **Dexie**: nueva versión con tabla `coachMessages: 'id, createdAt, deletedAt'`.
- **Drizzle/Neon**: tabla `coach_messages` (columnas sync estándar `id` PK + `user_id`, `rol`, `contenido`, `created_at`, `updated_at`, `deleted_at`, `server_updated_at`). Migración por **DDL directo** (script `scripts/migrate-*.mjs`, patrón ya usado).
- **Sync**: registrar `coachMessages`/`coach_messages` en los 3 registros (`collect`, `apply`, `server-tables`) — patrón idéntico a `gyms`/`exercisePhotos` (sin `shouldSync`). Backup: incluir en export/import (subir versión).
- Repo `lib/repositories/coach.ts`: `listMessages()` (activos, orden por createdAt), `addMessage(rol, contenido)`, `clearThread()` (tombstone de todos — opcional para "borrar conversación").

## Snapshot — `construirSnapshot()` (cliente)

Función que reúne las señales ya existentes en un objeto compacto (no se persiste; se arma fresco en cada envío). Incluye, filtrado por el gimnasio activo si aplica:
- `estancados`: de `listEstancados(gymId)` (nombre + sesiones sin mejorar).
- `semana`: de `getWeeklySummary(gymId)` (sesiones, volumen, deltaPct) + `getPRsThisWeek(gymId)` (PRs).
- `volumenPorGrupo`: de `getVolumeByMuscle(sinceTs, gymId)` (top grupos) + grupos descuidados de `getLastTrainedByMuscle(gymId)` (días sin entrenar).
- `objetivoSemanal` y `objetivosVolumen` (de los ajustes sincronizados).
- Acotado en tamaño (top-N por sección) para controlar coste y foco.

Vive en `lib/coach-snapshot.ts` (puro respecto a sus entradas; recibe los resultados de los repos o los consulta — diseñar como función testeable con datos mock).

## Ruta `POST /api/coach`

- `auth()` de Clerk → 401 sin sesión (patrón de `/api/exercise-photos`).
- 503 si falta `DEEPSEEK_API_KEY` (env no configurada), como hace la ruta de fotos con R2.
- Body: `{ mensajes: {rol, contenido}[], snapshot }`. Se envían solo los **últimos N mensajes** + el snapshot (coste acotado).
- System prompt: persona de coach de fuerza/hipertrofia en español, conciso y accionable; se le pasa el snapshot serializado; **disclaimer** (orientación general, no consejo médico).
- `streamText` con el provider DeepSeek (`DEEPSEEK_API_KEY`); responde el stream al cliente.

## UI

- **`/coach`**: pantalla de chat estilo Brutalist Iron. Lista de mensajes (usuario/asistente diferenciados), input + botón enviar, **streaming** vía el hook de chat del AI SDK (`useChat`) sembrado desde el hilo de Dexie. Al terminar cada respuesta, persistir en Dexie (se sincroniza). Nota de disclaimer visible.
- **Insights rápidos**: chips que pre-rellenan y envían una pregunta: "Analiza mi semana", "¿Por qué estoy estancado?", "¿Qué ejercicio sustituyo?".
- **Acceso**: icono (Lucide, ej. `Sparkles`/`MessageCircle`) en `auth-header` (junto al indicador de sync) que enlaza a `/coach`; tarjeta "Pregunta al coach" en Home (Entrenar), junto al resumen semanal.

## Seguridad / coste

- `DEEPSEEK_API_KEY` SOLO en servidor (env de Vercel, no en `.env.local` del cliente). Ruta protegida con Clerk.
- Disclaimer en system prompt y en la UI.
- Coste acotado: se mandan los últimos N mensajes + snapshot top-N; uso personal (un usuario) → sin rate-limit dedicado, pero la ruta exige sesión.
- PENDIENTE deploy: añadir `DEEPSEEK_API_KEY` a Vercel Production (vía `vercel env add`) y ejecutar la migración Neon de `coach_messages` antes de desplegar el código.

## Estrategia de tests

- **`construirSnapshot`** (puro): con señales mock, produce el objeto compacto esperado (top-N, campos correctos, gym-filtrado).
- **Ruta `/api/coach`**: 401 sin auth; con auth + DeepSeek **mockeado**, responde 200/stream y pasa el snapshot al prompt; 503 sin `DEEPSEEK_API_KEY`. (El proyecto ya testea rutas en el proyecto vitest `api` en node, ver `vitest.config`.)
- **`CoachMessage`/sync**: collect/apply incluyen `coachMessages`; backup round-trip; repo `addMessage`/`listMessages` (orden, tombstone).
- **Componente chat**: renderiza el hilo, envía un mensaje (provider/transport mockeado), persiste en Dexie.
- El streaming real y la llamada a DeepSeek no se testean en unit (se mockea el provider del AI SDK).

## Casos límite

- **Sin `DEEPSEEK_API_KEY`** (dev local) → 503 con mensaje claro; la UI muestra "coach no disponible".
- **Offline** → el envío falla con gracia (mensaje de error en el chat); los mensajes ya guardados se ven (Dexie). El mensaje del usuario podría quedar sin respuesta hasta reintentar.
- **Sin datos aún** (usuario nuevo) → el snapshot va casi vacío; el coach responde con orientación general.
- **Respuesta cortada / error del proveedor** → mostrar error en el chat; no se persiste media respuesta (persistir solo `onFinish`).
- **Sync del hilo**: LWW estándar por `id` (mensajes son inmutables salvo tombstone; conflictos prácticamente imposibles).

## Fases de implementación

Cada fase su propio plan, testeable/desplegable de forma incremental:
- **B1**: entidad `CoachMessage` sincronizada (Dexie + repo + Drizzle + migración Neon + collect/apply/server-tables + backup).
- **B2**: `construirSnapshot()` (cliente, puro) sobre las señales de A/C.
- **B3**: ruta `POST /api/coach` + deps del AI SDK + provider DeepSeek (`DEEPSEEK_API_KEY`).
- **B4**: pantalla `/coach` (chat streaming sembrado/persistido en Dexie + insights rápidos).
- **B5**: accesos (icono en `auth-header` + tarjeta en Home).

## Fuera de alcance

- Múltiples conversaciones/hilos (solo uno).
- Tool-calling / que el modelo consulte stats en vivo (incompatible con local-first; el snapshot lo cubre).
- AI Gateway (se usa DeepSeek directo por decisión del usuario).
- Generación de rutinas completas por IA / edición de datos por el coach (solo aconseja; no muta entidades).
