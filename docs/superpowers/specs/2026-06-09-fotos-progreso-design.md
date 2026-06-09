# Fotos de progreso (D2) — Diseño

**Fecha:** 2026-06-09
**Estado:** aprobado para plan
**Contexto:** Segunda sub-feature del pilar **D — Seguimiento corporal** (la primera, D1 peso+medidas, ya está en producción). D2 añade fotos corporales con fecha, etiquetadas por ángulo, con galería y comparación antes/después lado a lado. Reusa la infraestructura de imágenes ya montada (R2 + compresión + patrón de ruta de fotos de ejercicio).

**Decisiones tomadas (brainstorming):**
- Comparación **lado a lado** (dos fotos en paralelo), no slider.
- Fotos **etiquetadas por ángulo**: `frente` / `lado` / `espalda` (conjunto fijo). Galería y comparación se agrupan por ángulo.
- **Privacidad por presentación**: miniaturas difuminadas (`blur`) que se revelan al tocar. La seguridad real ya está en el login (Clerk) + key de R2 namespaced por usuario (anti-IDOR).
- Cada foto guarda **foto + fecha + nota** (nota opcional).
- Integrada como **sección dentro de `/cuerpo`** (debajo de las métricas), no como pestaña ni sub-pantalla.

---

## Datos — entidad `ProgressPhoto`

```ts
type AnguloFoto = 'frente' | 'lado' | 'espalda';

interface ProgressPhoto extends SyncMeta {   // id, userId, updatedAt, deletedAt
  userId: string | null;
  url: string;          // URL pública en R2
  key: string;          // object key en R2 (namespaced por userId)
  fecha: number;        // epoch ms (cuándo se tomó; editable, hoy por defecto)
  angulo: AnguloFoto;
  nota: string | null;  // texto corto opcional
}
```
- IDs UUID (patrón estándar).
- **Dexie**: nueva versión **v14**, tabla `progressPhotos: 'id, fecha, angulo, deletedAt'`.
- **Drizzle/Neon**: tabla `progress_photos` (`...sync` estándar + `url text NOT NULL`, `key text NOT NULL`, `fecha bigint NOT NULL`, `angulo text NOT NULL`, `nota text` nullable). Migración DDL directa (`scripts/migrate-progress-photos.mjs`, patrón idempotente `CREATE TABLE IF NOT EXISTS`).
- **Sync**: registrar `progressPhotos`/`progress_photos` en los 3 registros (collect sin `shouldSync`, apply `TABLE_BY_NAME`, server-tables `SERVER_TABLES`) + backup (**subir a versión 10**). Push genérico (`target: table.id`) sirve (id PK estándar).
- Repo `lib/repositories/progress-photos.ts`:
  - `listPhotos()` → fotos activas, orden **fecha desc** (galería: más reciente primero).
  - `addPhoto(input: { url; key; fecha; angulo; nota })` → crea `ProgressPhoto` (timestamp único para `updatedAt`).
  - `deletePhoto(id)` → tombstone (`deletedAt`/`updatedAt`); **devuelve el `key`** para limpiar el objeto en R2 best-effort.

La foto se almacena normal en R2; el difuminado es puramente presentación CSS y no afecta al almacenamiento ni al sync.

## API — `/api/progress-photos` (espeja `app/api/exercise-photos/route.ts`)

- **POST**: `auth()` → 401 si no hay sesión · `r2Configured()` → 503 si falta R2 · acepta `FormData` con `file` (Blob) · valida `file.type` empieza por `image/` (400) y `file.size ≤ 8MB` (413) · key = `` `${userId}/progress/${crypto.randomUUID()}.jpg` `` · `putImage(key, bytes, 'image/jpeg')` · devuelve `{ url: publicUrl(key), key }`.
  - El ángulo, la fecha y la nota **no** viajan por la ruta: los guarda la entidad sincronizada en el cliente tras recibir `{url, key}`. La ruta es solo la frontera de confianza para subir el objeto a R2.
- **DELETE**: `auth()` → 401 · `r2Configured()` → 503 · body JSON `{ key }` · **anti-IDOR**: 403 si `key` no empieza por `` `${userId}/` `` · `deleteR2Object(key)` envuelto en try/catch (best-effort, ignora objeto inexistente) · devuelve `{ ok: true }`.

## UI — sección "Fotos de progreso" en `/cuerpo`

Debajo de las tarjetas de métricas. Estética Brutalist Iron. Tres partes:

1. **Subir** (`components/progress-photo-upload.tsx`): input de archivo (`accept="image/*"`, `capture="environment"` para cámara en móvil) + selector de ángulo (Frente/Lado/Espalda) + fecha (`<input type="date">`, hoy por defecto, mismo helper `hoyISO` y conversión a epoch que el registro de métricas) + nota opcional (input corto). Al elegir archivo: `compressImage(file)` → `POST /api/progress-photos` → `{url, key}` → `addPhoto({ url, key, fecha, angulo, nota })`. Estados inline: "Subiendo…", error (incluye "R2 no configurado" si 503), reset tras éxito.
2. **Galería** (`components/progress-gallery.tsx`): recibe las fotos activas (por props desde la sección, que hace el `useLiveQuery`), agrupadas por ángulo en el orden Frente → Lado → Espalda; dentro de cada ángulo, miniaturas en grid, orden fecha desc. Cada miniatura: `<img>` con clase `blur` por defecto; al tocar se revela (estado local `Set<id>` de reveladas, toggle). Muestra fecha + nota; botón borrar (`✕`, `aria-label`) → `deletePhoto(id)` + limpieza R2 best-effort vía `DELETE`.
3. **Comparar lado a lado** (`components/progress-compare.tsx`): por ángulo (selector de ángulo si hay varios con datos), dos selectores de foto (A / B; por defecto A = más antigua, B = más reciente del ángulo) que muestran ambas en paralelo (`grid grid-cols-2`) con su fecha bajo cada una. Solo se ofrece si el ángulo tiene ≥2 fotos.

La sección compone los tres componentes; un único `useLiveQuery(listPhotos)` provee los datos (galería y comparación son presentacionales por props → tests deterministas).

## Manejo de errores
- Subida: si la respuesta no es OK (503 sin R2, 413 grande, 400 tipo), mostrar mensaje inline y no crear la entidad local.
- Borrado: tombstone local primero; la llamada `DELETE` a R2 es best-effort (try/catch silencioso) — un fallo de red no debe dejar la UI inconsistente.
- Sin fotos → la sección invita a subir la primera.

## Estrategia de tests
- **Ruta** `/api/progress-photos` (proyecto `api`, node): 401 sin sesión, 503 sin R2, 400 tipo no imagen, 413 >8MB, POST OK devuelve `{url,key}` con key namespaced, DELETE 403 si key ajeno, DELETE OK. Mock de `auth`, `r2Configured`, `putImage`, `deleteR2Object`, `publicUrl` (mirror de los tests de exercise-photos si existen; si no, crear).
- **Repo** `progress-photos.ts` (fake-indexeddb): `addPhoto` crea con campos correctos; `listPhotos` orden fecha desc y excluye tombstones; `deletePhoto` marca tombstone y devuelve el key.
- **Sync**: collect/apply incluyen `progressPhotos` (convergencia por id); backup round-trip (versión 10).
- **Componentes**:
  - upload: elegir archivo dispara `compressImage` + `fetch('/api/progress-photos')` + `addPhoto` con `{url,key,fecha,angulo,nota}`; error de subida no llama a `addPhoto`.
  - galería: agrupa por ángulo, miniatura difuminada por defecto y revelada tras click; borrar llama `deletePhoto(id)`.
  - compare: con ≥2 fotos de un ángulo muestra dos imágenes; los selectores cambian A/B.

## Casos límite
- **Sin R2 configurado** → POST 503; la UI muestra "R2 no configurado" y no crea entidad. (En local/CI sin R2, la subida está deshabilitada igual que las fotos de ejercicio.)
- **Ángulo con una sola foto** → galería la muestra; comparación de ese ángulo no se ofrece (necesita ≥2).
- **Foto borrada**: tombstone sincroniza; el objeto R2 se intenta borrar best-effort (si falla, queda huérfano, aceptable).
- **Fecha futura**: permitida (no bloqueante), como en métricas.
- **Nota vacía** → se guarda `null`.

## Fases de implementación (D2)
- **D2a** — Fundación: entidad `ProgressPhoto` sincronizada (Dexie v14 + repo + Drizzle + collect/apply/server-tables + backup v10 + DDL Neon) **+ ruta `/api/progress-photos`** (POST/DELETE con tests).
- **D2b** — Subida + galería: `progress-photo-upload` (compress→POST→addPhoto) + `progress-gallery` (agrupada por ángulo, blur reveal-on-tap, borrar) + sección "Fotos de progreso" en `/cuerpo`.
- **D2c** — Comparación lado a lado: `progress-compare` integrado en la sección.

## Fuera de alcance
- Slider antes/después (se decidió lado a lado; posible iteración futura).
- Ángulos personalizables (conjunto fijo frente/lado/espalda).
- Cifrado real de las fotos / álbum protegido por PIN (la privacidad es presentacional + login).
- Importación desde galería del sistema en lote, álbumes, etiquetas libres.
- Bloqueo/wearable/IA de estimación de % graso por foto.
