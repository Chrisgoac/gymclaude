# Android fase 6: cualificación de la release

Base cualificada: commit `c58b2520b102f9a2d2d1f8668763199e3a671d4a` (rama `feat/android-release`, que contiene
`feat/android-workouts`, `feat/android-insights` y `feat/android-media-ai`). Fecha: 2026-10-12.
JDK: JBR de Android Studio (`JAVA_HOME=/home/chrisgoac/android-studio/jbr`), `ANDROID_HOME=~/Android/Sdk`,
build-tools 37.0.0. BD desechable: `postgresql://postgres:***@127.0.0.1:5432/gymlog_sync_test` (nunca producción).
Google Play, Clerk, R2 y DeepSeek son dobles de prueba en los tests: no hubo llamadas a servicios reales.

**Estado: la parte automatizada está en verde. Todo lo que exige dispositivos físicos, Play Console, una cuenta real
de Clerk o servicios reales está PENDIENTE DEL DUEÑO (sección 5). No se subió nada a Play ni se desplegó nada.**

Alcance acordado (ruling de la fase 6): la Task 9 ejecuta solo la cualificación automatizada; los pasos de
dispositivos y de Play quedan como checklist del dueño. La Task 10 (envío a producción) no se ejecuta sin
aprobación explícita del dueño.

## 1. Backend web (raíz del repo)

| Comando | Resultado |
|---|---|
| `SYNC_TEST_DATABASE_URL=postgresql://postgres:***@127.0.0.1:5432/gymlog_sync_test npx vitest run` | 122 ficheros, **1262/1262 tests**, 0 omitidos (las suites condicionadas a BD se ejecutaron) |
| `npm run lint` | exit 0, sin avisos |
| `npx tsc --noEmit` | exit 0 |
| `npm run build` | exit 0, `✓ Compiled successfully`; `○ /privacy` estática, `ƒ /delete-account` dinámica |

`npm run build` carga `.env.local` (producción) solo para compilar; las páginas comprobadas son estáticas o no se
ejecutaron. No se lanzó ningún script ni test contra `.env.local` / `.env.production.vercel`.

## 2. Android (desde `android/`)

| Comando | Resultado |
|---|---|
| `./gradlew --no-build-cache clean testDebugUnitTest :core:model:test :core:domain:test :core:testing:test verifyModuleGraph lintDebug assembleDebug` | BUILD SUCCESSFUL (4 min 3 s, 0 tareas desde caché). **1188 tests, 0 fallos, 0 errores, 1 omitido** (el test solo-release `ReleaseConfigTest` en debug). `verifyModuleGraph` + `verifyUniqueResourceNames` OK. lintDebug: **0 errores**, 80 avisos en 15 módulos (app 26) |
| `./gradlew --no-build-cache clean testReleaseUnitTest lintRelease bundleRelease assembleRelease` con `GYMLOG_KEYSTORE_*` apuntando a un keystore PKCS12 desechable fuera del repo (borrado al terminar) | BUILD SUCCESSFUL (2 min 22 s). `ReleaseConfigTest` **7/7**. lintRelease **0 errores** (mismos avisos). R8 sin clases ausentes |
| `./gradlew connectedDebugAndroidTest` en AVD `gymlog_api36` | BUILD SUCCESSFUL. **20/20**: `app` 11 (`NavigationSmokeTest` 6, `FreeParityE2eTest` 3, `WorkoutOfflineE2eTest` 2), `core:database` 5 (`UserIsolationTest`), `feature:progress` 4 (`ProgressAccessibilityTest`) |
| `./gradlew --stop` | daemons parados |

Tests por módulo (unitarios debug): app 249, core:data 271, core:domain 185, core:network 61, core:database 40,
core:model 12, core:designsystem 8, feature:settings 80, progress 84, train 44, routines 37, coach 26,
history 33, exercises 22, body 18, auth 15, achievements 3.

Incidencia de entorno: el primer `connectedDebugAndroidTest` falló con `INSTALL_FAILED_UPDATE_INCOMPATIBLE` porque
el emulador conservaba el APK release firmado con la clave desechable de la Task 8 (mismo `applicationId`). Tras
`adb uninstall com.chrisgoac.gymlog` la ejecución pasó entera. No es un fallo del código.

No existe AVD de API 26 en la máquina y, por instrucción, no se descargaron imágenes de sistema: la ejecución en
API 26 queda pendiente (sección 5).

### Paridad de fixtures TypeScript ↔ Kotlin

Los 14 fixtures compartidos se leen desde el repo en las dos suites anteriores (ambas en verde):
`contracts/domain/{achievement-cases,body-stats-cases,fecha-cases,insight-cases,num-cases,period-cases,progression,routine-rotation}.json`
y `contracts/v1/fixtures/{bootstrap-all-entities,conflict-cases,pull-page,pull-pagination-cases,push-invalid-server-field,push-valid}.json`.
Comprobado con `rg -l -F <fixture> -g '*.test.ts' .` y `rg -l -F <fixture> -g '*.kt' android`: cada fixture tiene
≥1 consumidor en cada runtime.

### Marcadores y espacios

- `rg -n "[T]BD|[T]ODO|[F]IXME|place''holder|coming[ ]soon" android app/api/v1 lib/api/v1 contracts docs/play` → sin coincidencias (exit 1).
- `git diff --check` → limpio.

## 3. Artefactos (candidato técnico, NO el AAB de subida)

El AAB está firmado con una clave desechable (`CN=GymLog Throwaway Test`, borrada). El AAB que se suba a Play lo
firmará el dueño con su clave de subida (o CI en `main`/`v*`), por lo que su hash será distinto; repetir esta
tabla con ese artefacto.

| Dato | Valor |
|---|---|
| Commit | `c58b2520b102f9a2d2d1f8668763199e3a671d4a` |
| applicationId | `com.chrisgoac.gymlog` |
| versionCode / versionName | `1` / `1.0.0` (por defecto; CI usa `GITHUB_RUN_NUMBER + VERSION_CODE_OFFSET`) |
| minSdk / targetSdk / compileSdk | 26 / 36 / 37 |
| `app-release.aab` (8 908 213 B) SHA-256 | `3a9d810f7ee0303b65430974520e9bfa384029e69742da4ced8e5d76e1a1e304` (`jarsigner -verify` → `jar verified.`) |
| `app-release.apk` (4 463 165 B) SHA-256 | `42f7536365f00ee22592f346ac52d383a6761f9b2aaa1bc859c1dc0c892da93d` (`apksigner`: V2, `CN=GymLog Throwaway Test`) |
| `mapping.txt` SHA-256 | `4f1794b77d0b7da0afd3a6a3a979fcd1cc16366387cfb8b4b3bfc974f10f4fd9` |
| Desofuscación | el AAB incluye `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`: Play lo asocia solo al subir el bundle |
| Símbolos nativos | sin código nativo propio; solo `.so` precompilados de AndroidX (`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`), sin símbolos que subir |
| Ediciones | un único módulo `app`, sin `productFlavors`: Pro es un derecho del backend, no otro APK |

Inspección (`aapt2 dump badging`, `dumpsys package`): `pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]` (no
depurable). Permisos y componentes exportados sin cambios respecto a `docs/play/permissions-inventory.md`
(Task 8).

## 4. E2E de la sección 17.4 en el emulador (sin inicio de sesión real)

La API nativa de Clerk está deshabilitada en la instancia (`native_api_disabled` en logcat), así que ningún flujo
que requiera sesión se puede ejecutar todavía. APK release minificado, firmado con la clave desechable, en AVD
`gymlog_api36` (Pixel 6, API 36):

| Paso | Resultado |
|---|---|
| `adb install -r app-release.apk` | Success |
| `am start -W` (arranque en frío) | `Status: ok`, TotalTime 1410 ms; segundo arranque tras `force-stop` 632 ms; proceso vivo |
| Pantalla | inicio de sesión: GYMLOG, Entrar / Crear cuenta, EMAIL, Enviar código, Usar contraseña, Continuar con Google, y el aviso «No se puede conectar con el servicio de cuentas… el acceso desde la app aún no está habilitado» |
| logcat del pid | 0 `FATAL` / `ClassNotFound` / `NoSuchMethod` / `NoClassDefFound` / `ClassCastException` / `SerializationException`; contiene `native_api_disabled` |
| Limpieza | `adb uninstall com.chrisgoac.gymlog`, `adb emu kill` |

| # | Flujo 17.4 | Estado |
|---|---|---|
| 1 | Login con la cuenta existente y restauración cloud completa | Solo pantalla de login verificada. **PENDING-OWNER** (necesita Clerk Native API) |
| 2 | Entreno offline, cierre/reapertura, finalización y sync posterior | Cubierto en JVM y por `WorkoutOfflineE2eTest` (sin servidor real). Contra producción: **PENDING-OWNER** |
| 3 | Edición histórica offline y convergencia en un segundo dispositivo | Arnés JVM de dos dispositivos (fase 3). Real: **PENDING-OWNER** |
| 4 | Foto offline, subida al recuperar red, lectura con URL firmada | Rutas reales + R2 falso (fase 5). Real: **PENDING-OWNER** |
| 5 | Compra Pro, Coach, restauración y revocación | Adaptadores falsos (fase 6). Real: **PENDING-OWNER** |
| 6 | Borrado total de cuenta y ausencia de acceso posterior | BD desechable + Clerk/R2 falsos (Task 5/6). Real: **PENDING-OWNER** |

### Matriz de dispositivos

| Dispositivo | Unit/lint/build | Instrumentados | E2E 17.4 |
|---|---|---|---|
| JVM (Robolectric) | ✅ 1188 + 7 release | n/a | n/a |
| Emulador API 36 (`gymlog_api36`, Pixel 6) | n/a | ✅ 20/20 | solo arranque + login (release minificado) |
| Emulador API 26 | — | PENDING-OWNER (no hay AVD; no se descargó imagen) | PENDING-OWNER |
| Pixel físico | — | PENDING-OWNER | PENDING-OWNER |
| Samsung físico (One UI) | — | PENDING-OWNER | PENDING-OWNER |

## 5. Comprobaciones manuales PENDING-OWNER

Requisitos previos de todas ellas (ver `docs/play/launch-checklist.md`): ramas mergeadas, migraciones aplicadas,
variables de entorno puestas, Clerk Native API habilitada con el paquete y las huellas SHA-256, AAB firmado con la
clave de subida y publicado en un track interno.

- [ ] **PENDING-OWNER — matriz completa.** En emulador API 26 (`sdkmanager "system-images;android-26;google_apis;x86_64"` + AVD), emulador API 36, un Pixel y un Samsung: `./gradlew connectedDebugAndroidTest` (salvo en físicos sin USB: instalar desde el track interno) y los 6 flujos de abajo. Anotar modelo, versión de Android y resultado.
- [ ] **PENDING-OWNER — Flujo 1, login y restauración.** Con la cuenta existente de la PWA: instalar desde el track interno → Entrar → «Usar contraseña» (o código) → esperar el bootstrap → comparar en la app y en la PWA el número de entrenos, rutinas, ejercicios personalizados, medidas, logros y fotos. Ninguna diferencia.
- [ ] **PENDING-OWNER — Flujo 2 / modo avión.** Con sesión iniciada: activar modo avión → iniciar entreno de rutina → registrar series, reordenar → forzar cierre (deslizar desde recientes) → reabrir: el entreno sigue activo con las series → finalizar → editar un entreno antiguo desde Historial y guardar → desactivar modo avión → en Ajustes, estado de sync «Sincronizado» sin cuarentenas → abrir la PWA: el entreno y la edición están.
- [ ] **PENDING-OWNER — Flujo 3, conflicto entre dos dispositivos.** Dispositivos A y B con la misma cuenta y sincronizados. Ambos en modo avión. En A editar el peso de la serie 1 de un entreno a 100; en B, más tarde, la misma serie a 105 y borrar otra serie. Reconectar A, luego B; sincronizar dos veces en cada uno. Ambos (y la PWA) muestran 105 y la serie borrada ausente. Repetir reconectando en orden inverso.
- [ ] **PENDING-OWNER — Flujo 4 y aislamiento de fotos privadas.** En modo avión hacer una foto de progreso → reconectar → se sube una vez. En la PWA, la foto abre vía `/api/media/read/<id>` (302 a URL firmada que caduca en minutos; copiar la URL firmada y comprobar que pasados 5 min da 403). Con una segunda cuenta: pedir `GET /api/media/read/<id de la primera>` → 404. Cerrar sesión en Android con la cuenta 1 e iniciar con la 2: no aparece ninguna miniatura de la cuenta 1 (ni en modo avión). Comprobar en el bucket que el objeto está en `R2_PRIVATE_BUCKET` bajo `users/<hash>/…`, no en el público.
- [ ] **PENDING-OWNER — Flujo 5, Billing (testers de licencia).** Añadir la cuenta Google del dispositivo a Play Console → Configuración → Pruebas de licencia. Con `gymlog_pro_monthly` y `gymlog_pro_annual` activos: (a) Ajustes → Pro → suscribir mensual → la app muestra Pro solo después de que `POST /api/v1/billing/verify` responda (comprobar fila en `subscription_entitlements` con `source='play'`) → Coach responde. (b) Cambiar a anual: sigue habiendo UNA suscripción (Play → Suscripciones). (c) Desinstalar, reinstalar, iniciar sesión → «Restaurar compras» → Pro sin volver a pagar. (d) En Play Console → Gestión de pedidos, reembolsar y revocar → llega la RTDN (`billing_events`) → Coach responde `PRO_REQUIRED` y los datos Free siguen legibles/exportables. (e) Cancelar y dejar caducar (las suscripciones de prueba renuevan cada pocos minutos): Pro se pierde, los datos no.
- [ ] **PENDING-OWNER — informe de IA.** En Coach, sobre una respuesta del asistente pulsar «Reportar» y confirmar → aparece una fila en `reported_ai_responses`. Decidir y verificar el reporte en mesociclo e informe inteligente (marcador #14 de `docs/play/ai-content.md`).
- [ ] **PENDING-OWNER — Flujo 6, borrado completo de cuenta (incluido `fva`).** Con una cuenta de prueba con datos, fotos y (opcional) suscripción de tester: Ajustes → Cuenta → Borrar cuenta → escribir ELIMINAR → reverificar (contraseña o código). Antes, en el servidor, confirmar que el token de sesión Android lleva el claim `fva` (decodificar el JWT del header `Authorization` en los logs de una petición de prueba en preview, nunca en logs ordinarios de producción) y que Clerk tiene la reverificación habilitada; sin `fva` reciente (< 10 min) la API debe responder `REAUTH_REQUIRED`. Tras borrar: la app vuelve a «Cuenta borrada» y luego al login; el usuario no existe en Clerk; las 14 tablas sincronizadas no tienen filas de ese usuario; `account_deletion_jobs` llega a `done` (el barrido final `database_sweep` corre en el cron diario de las 04:30 UTC); el prefijo `users/<hash>/` del bucket privado está vacío; iniciar sesión de nuevo con ese email crea una cuenta vacía. Repetir el borrado desde la web pública `/delete-account`.
- [ ] **PENDING-OWNER — informe previo al lanzamiento.** Subir el AAB firmado a un track interno o cerrado (nunca producción), con la cuenta de revisión de `docs/play/reviewer-instructions.md` en «Credenciales de acceso». Esperar el pre-launch report y resolver todo hallazgo crítico de estabilidad, compatibilidad, rendimiento, privacidad o accesibilidad. Anotar aquí la fecha y el resultado.
- [ ] **PENDING-OWNER — Android Vitals y desofuscación.** En App bundle explorer, confirmar que la versión subida muestra el archivo de desofuscación (va dentro del AAB; si no aparece, subir `android/app/build/outputs/mapping/release/mapping.txt` del mismo build). No hay símbolos nativos que subir. Tras el track interno, revisar Vitals (crashes/ANR) y que las trazas salen desofuscadas.
- [ ] **PENDING-OWNER — ficha y declaraciones.** Credenciales de prueba, países y precios, productos mensual y anual (sin prueba gratuita ni promoción), Data Safety (`docs/play/data-safety.md`), clasificación de contenido, URL de privacidad `https://gym.chrisgoac.dev/privacy` con `PRIVACY_POLICY_APPROVED=true`, URL de borrado `https://gym.chrisgoac.dev/delete-account`, declaración de IA (`docs/play/ai-content.md`).
- [ ] **PENDING-OWNER — 12 testers / 14 días (si la cuenta de desarrollador es personal y se creó después del 13-nov-2023).** Crear un closed test, reunir ≥12 testers que acepten la invitación y mantenerlos 14 días seguidos; guardar captura de la pantalla de requisitos de Play Console con la fecha de inicio y fin antes de solicitar producción.
- [ ] **PENDING-OWNER — accesibilidad en físico.** TalkBack, escala de fuente 200 %, tema claro/oscuro e insets de gestos en Pixel y Samsung.

## 6. Riesgos no bloqueantes conocidos

- Los adaptadores de Google Play (Developer API y verificación JWT de Pub/Sub) solo se han probado con dobles: los fallos de integración aparecerían con credenciales reales (flujo 5).
- Tokens guardados solo como hash: si una RTDN de revocación se pierde, el Pro dura hasta la caducidad (ruling Task 2).
- Compras sin `obfuscatedAccountId` (resuscritas desde la Play Store) no se pueden restaurar: hueco de soporte documentado.
- La suscripción de Play no se cancela sola al borrar la cuenta: la app y `/delete-account` lo explican.
- Rutas heredadas de la PWA `/api/coach` y `/api/coach/mesociclo` siguen sin gate Pro (decisión del dueño).
- Solo las respuestas de Coach se pueden reportar en la app; mesociclo e informe inteligente no (riesgo de política de IA generativa).
- Icono de lanzador provisional (diseño propio) y falta el icono de 512 px de la ficha.
- Las candidatas menores diferidas de las tareas 1–8 (ver `progress.md`) quedan para la revisión final de la rama.
