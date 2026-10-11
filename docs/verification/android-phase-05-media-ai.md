# Android fase 5: medios privados e IA Pro, verificación

Base: `c298650` (feat/android-media-ai) + los tests de esta tarea; el commit de la tarea es
`test: verify private media and Pro AI`. Fecha: 2026-10-11. JDK: JBR de Android Studio.
BD desechable: `postgresql://postgres:***@127.0.0.1:5432/gymlog_sync_test` (nunca producción, nunca `.env.local`).
R2 y DeepSeek son dobles de prueba: no hay llamadas de red reales ni claves reales. Entitlement Pro sembrado con
`scripts/seed-test-entitlement.mjs` (`source='test'`, inerte con `NODE_ENV=production`).
Emulador: AVD `gymlog_api36` (Pixel 6, API 36), headless, apagado con `adb emu kill`.

**Estado del gate: evidencia automatizada completa; comprobaciones en dispositivo y con servicios reales pendientes.**

## 1. Migraciones en la BD desechable

```
MIGRATION_DATABASE_URL=<BD desechable> node scripts/migrate-sync-foundation.mjs   -> OK (0001, idempotente)
MIGRATION_DATABASE_URL=<BD desechable> node scripts/migrate-private-media.mjs     -> 0002_private_media_keys OK
MIGRATION_DATABASE_URL=<BD desechable> node scripts/migrate-ai-entitlements.mjs   -> 0003_ai_and_entitlements OK
```

## 2. Evidencia de integración del backend (rutas reales + Postgres real + R2/modelo falsos)

Fichero: `app/api/v1/__e2e__/media-ai.e2e.test.ts` (condicionado a `SYNC_TEST_DATABASE_URL`). Usa los route handlers
reales, el pool real, el rate limit real, el lector de entitlements real y `setAuthSourceForTests`.

```
SYNC_TEST_DATABASE_URL=<BD desechable> npx vitest run --project api app/api/v1/__e2e__
  -> 1 file, 9 tests passed
```

| Requisito del brief | Test |
|---|---|
| Subir, leer con URL firmada breve (300 s, no cacheable), la fila guarda `objectKey` y la ruta `/api/media/read/<id>`, nunca URL firmada | `upload → short read grant → second user gets 404 …` |
| Segundo usuario no puede leer ni borrar el id (404 indistinguible de inexistente; fila y objeto intactos) | mismo test |
| Borrado: tombstone + cola `media_deletions`; el objeto no se borra antes del plazo (24 h) y sí después | mismo test |
| Foto de ejercicio solo sobre ejercicio propio personalizado; reintento con el mismo id guarda un objeto | `an exercise photo requires …` |
| Free recibe `403 PRO_REQUIRED` en coach, mesocycles y reports con cualquier cuerpo (vacío, no JSON, `{}`, válido); sin llamada al modelo ni filas | `Free user gets 403 PRO_REQUIRED …` |
| Entitlement no concedente (EXPIRED) sigue siendo Free; el Pro de un usuario no desbloquea a otro | `an expired or non-granting …`, `Pro granted to one user …` |
| Coach: stream, interrupción a mitad (error reintentable, fila `incomplete`), reintento con el mismo `clientMessageId` completa la misma respuesta (una fila), un tercer reintento reproduce sin llamar al modelo (2 llamadas en total) | `Coach: streams, survives an interruption …` |
| Coach: desconexión del cliente aborta el modelo, deja la respuesta `incomplete` y libera el lease | `Coach: a client disconnect …` |
| Mesociclo: borrador validado, no escribe datos del usuario, reintento idempotente sin llamar al modelo; mismo id con otros parámetros se rechaza | `mesocycle: returns a validated draft …` |
| Informe: caché por fingerprint (sin llamada), fuente cambiada regenera e invalida el informe anterior (una sola fila) | `report: fingerprint cache hit …` |

## 3. Evidencia Android JVM

Test añadido en `core/data` (`MediaRepositoryTest`, harness existente con `FakeMediaApi` y Room en memoria):
`phase gate - capture offline, restart, reconnect uploads once, cached photo shows offline and never to another account`:
captura sin red (cola local, nada enviado) → reapertura con instancia nueva (`pendingWork`) → reconexión → una sola subida
(un worker duplicado no sube otra) → instancia nueva sin URLs en memoria y sin red muestra la miniatura desde la caché privada
(0 descargas) → otra cuenta en el mismo dispositivo no ve fila ni fichero (`Unavailable`), y la copia de A queda intacta.
El resto del aislamiento ya lo cubren `files, caches and work stay inside the exact account`, `signed URLs live in memory only …`,
`a pulled tombstone evicts the cached copy …` y `forgetting an account drops its in-memory grants`.

```
cd android && ./gradlew :core:data:testDebugUnitTest --tests '*MediaRepositoryTest*'  -> 27 tests, 0 failures
```

## 4. Suites completas

| Comando | Resultado |
|---|---|
| `SYNC_TEST_DATABASE_URL=<BD desechable> npx vitest run` | 104 ficheros, 1073 tests passed |
| `npm run lint` | sin avisos |
| `npx tsc --noEmit` | limpio tras la ola de correcciones (antes: TS2578 preexistente en `scripts/export-android-catalog.test.ts`) |
| `NODE_OPTIONS=--max-old-space-size=4096 npm run build` | BUILD OK |
| `cd android && ./gradlew testDebugUnitTest test lintDebug assembleDebug verifyModuleGraph` | BUILD SUCCESSFUL; 1007 tests, 0 failures, 0 errors, 0 skipped |
| `./gradlew :app:connectedDebugAndroidTest` (AVD `gymlog_api36`, API 36) | 11 tests, 0 failures; emulador apagado (`adb emu kill`) |

## 5. Orden de despliegue (obligatorio)

La PWA y la sync ya seleccionan las columnas nuevas (`key`/`mime`/`bytes`/`width`/`height`, tablas de IA): si el
código llega a producción antes que las migraciones, el pull de la PWA falla. Orden:

1. **Migraciones primero**, contra la BD de producción y antes de que el código llegue a `main`/producción:
   `npm run db:migrate:media` y después `npm run db:migrate:ai` (0003 se editó en sitio antes de publicarse:
   una sola ejecución). Son idempotentes.
2. **Bucket R2 privado** nuevo (sin acceso público ni dominio público) y env `R2_PRIVATE_BUCKET` en Vercel (las
   credenciales `R2_ACCOUNT_ID`/`R2_ACCESS_KEY_ID`/`R2_SECRET_ACCESS_KEY` deben poder escribir en él). Sin esa env
   las rutas v1 de medios responden `503 MEDIA_UNAVAILABLE` (fallan cerradas; nunca escriben en el bucket público
   `R2_BUCKET`, que sigue sirviendo las fotos legacy de la PWA).
3. **`CRON_SECRET`** en Vercel (valor aleatorio largo). Vercel lo manda como `Authorization: Bearer …` al cron.
4. **Cron**: `vercel.json` declara `/api/internal/media/process-deletions` a diario (`0 4 * * *`). Tras desplegar,
   comprobar en Vercel → Settings → Cron Jobs que aparece y lanzar una ejecución manual: debe responder `200`
   (`401` = falta o no coincide `CRON_SECRET`; `503 MEDIA_UNAVAILABLE` = falta `R2_PRIVATE_BUCKET`).
5. Desplegar el código.

Borrado de objetos: el tombstone (ruta v1 DELETE, push v1 o push legacy de una foto v1, o el DELETE legacy de la
PWA con una clave v1) encola `media_deletions` en la misma transacción; el cron borra tras 24 h las claves
`users/…` del bucket privado y las legacy del público, y cancela el borrado si la fila del dueño vuelve a estar viva.

## 6. Comprobaciones manuales pendientes (no automatizables esta noche)

- [ ] Bucket R2 privado real: subida, URL firmada de 300 s, borrado diferido por el cron; CORS/CSP del bucket y de la PWA (hoy `next.config` no define CSP).
- [ ] Stream real de DeepSeek: `fullStream`, desconexión del cliente (`request.signal`) y tiempos con la clave real (smoke tras desplegar).
- [ ] Photo Picker y cámara reales en dispositivos físicos (Pixel/Samsung), rotación EXIF real, permisos.
- [ ] Ejecución en API 26 (emulador o dispositivo) de las pruebas instrumentadas y del flujo de fotos.
- [ ] Derechos Pro reales de Google Play (Plan 6): hoy solo existe el seed `source='test'`.
- [ ] Despliegue en el orden de la sección 5 (migraciones → bucket privado + `R2_PRIVATE_BUCKET` → `CRON_SECRET` → cron verificado → código).
- [ ] Decisión de producto: las rutas PWA heredadas `/api/coach` siguen sin guard Pro (solo `/api/v1/ai/*` lo exige); decidir si se cierran.
- [ ] Decisión de despliegue: migrar las fotos públicas antiguas de la PWA al bucket privado (coste: una redirección extra por imagen).
- [ ] Pendientes menores registrados en el ledger de fase (informes sin lease multi-dispositivo, descartes de Coach solo en memoria, etc.).

## 7. Ola de correcciones de la revisión final

Detalle y evidencia en `.superpowers/sdd/2026-08-31-gymlog-android-05-media-ai/final-fixes-report.md`. Resumen:

- Service worker: `/api/media/read/*` y `/api/v1/*` (same-origin) van siempre a red (`NetworkOnly` antes de `defaultCache`).
- `npx tsc --noEmit` limpio (TS2578 de `scripts/export-android-catalog.test.ts` resuelto).
- Medios v1 en bucket privado propio (`R2_PRIVATE_BUCKET`), 503 `MEDIA_UNAVAILABLE` sin él.
- Borrado diferido conectado: ruta interna autenticada por `CRON_SECRET` + cron de Vercel; tombstones de sync y DELETE legacy encolan.
- Android: una foto por ejercicio (cambiarla guarda la nueva y luego borra la anterior); caché de imagen por `mediaId` + `objectKey`.
- Android Coach: snapshot de la BD abierta del turno, PRs semanales ≤ 100, `Retry-After` ≤ 15 s, una caída de conexión tras `complete` no duplica el turno.
- IA: presupuesto diario de 200 llamadas por usuario (`aiDaily`, 429 con `Retry-After`); el informe en caché se sirve antes de comprobar DeepSeek o gastar cupo.

Suites tras la ola: `vitest` 108 ficheros / 1100 tests (con BD desechable), `npm run lint` limpio, `npx tsc --noEmit`
limpio, `npm run build` OK; Android `testDebugUnitTest test lintDebug assembleDebug verifyModuleGraph` BUILD SUCCESSFUL,
1016 tests, 0 fallos. No se relanzaron las pruebas instrumentadas.
