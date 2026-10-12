# GymLog Android: checklist de lanzamiento del dueño

Orden de ejecución. Cada bloque es una puerta: no se pasa al siguiente con casillas abiertas en el anterior, salvo
las marcadas como «puede ir en paralelo». Consolida los pendientes del dueño de las seis fases (ledgers
`progress.md` de `android-backend-sync`, `android-platform`, `android-workouts`, `android-insights`,
`android-media-ai` y `android-release`). Evidencia automatizada: `docs/verification/android-release-qualification.md`.

Reglas fijas: nada de secretos en git ni en logs; el envío a producción en Play Console exige aprobación explícita
del dueño (Task 10); los textos legales son borradores hasta que el dueño (y, si procede, su asesor) los aprueben.

## 0. Decisiones de producto pendientes (antes de congelar la release)

- [ ] Rutas heredadas de la PWA `/api/coach` y `/api/coach/mesociclo` (DeepSeek sin Pro): mantener, poner gate Pro o retirar (marcador #13). Si se ponen tras Pro, ajustar el texto de privacidad.
- [ ] «Reportar» en las pantallas de mesociclo e informe inteligente (política de IA generativa de Play), o aceptar el riesgo por escrito (marcador #14).
- [ ] Quién revisa `reported_ai_responses` y con qué frecuencia (marcador #15).
- [ ] Prorrateo al bajar de anual a mensual (hoy `WITH_TIME_PRORATION` en ambos sentidos).
- [ ] Bucket público heredado de la PWA: dejarlo como está o migrar las fotos antiguas al privado (las URLs antiguas son públicas y permanentes; la política ya lo dice).
- [ ] Hueco de soporte: suscripciones sin `obfuscatedAccountId` (resuscritas desde la Play Store) no se pueden restaurar. Decidir el texto de soporte.
- [ ] Precio mensual y anual, y países. Sin prueba gratuita ni promoción de entrada (restricción global de v1).
- [ ] Revisar las candidatas menores diferidas («FINAL-WAVE») de los ledgers; la revisión final de la rama decide cuáles se arreglan antes de publicar.

## 1. Merge de ramas (en este orden)

`feat/android-backend-sync` y `feat/android-platform` ya están en `main`. El resto está apilado: cada rama contiene
a la anterior.

- [ ] `feat/android-workouts` → `main` (tests web + Android en verde).
- [ ] `feat/android-insights` → `main`.
- [ ] `feat/android-media-ai` → `main`.
- [ ] `feat/android-release` → `main`.
- [ ] ⚠️ No desplegar a producción hasta completar el bloque 2: el código de `media-ai` y `release` lee columnas y tablas que crean las migraciones. Si el deploy de Vercel es automático al mergear a `main`, aplicar el bloque 2 ANTES del merge o pausar el auto-deploy.

## 2. Migraciones de producción (PUERTAS DURAS antes del deploy)

Cada script usa una transacción, un advisory lock y comprobaciones posteriores; con `.env.local` exige
`CONFIRM_TARGET`. Ejecutar en este orden, una vez, y guardar la salida:

- [ ] Confirmar que `0001_android_sync_foundation` ya está en producción (`npm run db:migrate:sync` es idempotente; runbook de la fase 1: migrar → desplegar → volver a ejecutarlo para el backfill de la ventana).
- [ ] `npm run db:migrate:media` → `0002_private_media_keys OK`.
- [ ] `npm run db:migrate:ai` → `0003_ai_and_entitlements OK` (0003 se editó en sitio antes de salir: ejecutarlo aunque ya existiera la tabla, comprueba las columnas añadidas después).
- [ ] `npm run db:migrate:billing` → `0004_billing_events OK`.
- [ ] `npm run db:migrate:account` → `0005_account_deletion_jobs OK`. Sin ella fallan sync, informes IA, verify de Play y RTDN (todas comprueban el bloqueo de borrado).

## 3. Infraestructura y variables de entorno (Vercel, producción)

- [ ] Crear el bucket privado de R2 (jurisdicción a elegir) y poner `R2_PRIVATE_BUCKET`. Sin ella los endpoints de medios v1 responden 503 `MEDIA_UNAVAILABLE`. Configurar CORS si el navegador sube directo.
- [ ] `CRON_SECRET` (aleatorio, ≥32 bytes).
- [ ] Verificar en Vercel que aparecen los dos crons de `vercel.json` (`/api/internal/media/process-deletions` 04:00 UTC y `/api/internal/account/process-deletions` 04:30 UTC) y que una ejecución manual con `Authorization: Bearer $CRON_SECRET` devuelve 200.
- [ ] `CLERK_SECRET_KEY` presente en runtime (borrado de identidad en Clerk).
- [ ] `DEEPSEEK_API_KEY` presente (Coach, mesociclos, informes).
- [ ] `PRO_USER_IDS` (opcional): ids de Clerk (`user_…`) separados por comas con Pro de cortesía, sin compra en Play (IA + `GET /api/v1/billing/entitlement`). Revisar antes del lanzamiento público que solo contiene las cuentas previstas.
- [ ] `GOOGLE_PLAY_PACKAGE_NAME=com.chrisgoac.gymlog`.
- [ ] `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: cuenta de servicio de Google Cloud con acceso a la Google Play Developer API, invitada en Play Console → Usuarios y permisos con permiso de ver datos financieros y gestionar pedidos/suscripciones.
- [ ] Pub/Sub: crear el topic de RTDN, darle `roles/pubsub.publisher` a `google-play-developer-notifications@system.gserviceaccount.com`, enlazarlo en Play Console → Monetización → Configuración de monetización, y crear una suscripción **push** autenticada hacia `https://gym.chrisgoac.dev/api/webhooks/google-play` con una cuenta de servicio de envío y una audiencia.
- [ ] `GOOGLE_PLAY_RTDN_AUDIENCE` (la audiencia de la suscripción push) y `GOOGLE_PLAY_RTDN_SERVICE_ACCOUNT` (el email de la cuenta de servicio de envío). Sin ellas el webhook no acepta notificaciones.
- [ ] Enviar una notificación de prueba desde Play Console y comprobar una fila en `billing_events`.
- [ ] Desplegar. Smoke: `/privacy` y `/delete-account` responden 200 sin sesión; la PWA sigue funcionando.

## 4. Clerk (dashboard de la instancia de producción)

- [ ] Habilitar la API nativa (hoy logcat muestra `native_api_disabled` y la app avisa en el login).
- [ ] Registrar la app Android: paquete `com.chrisgoac.gymlog` y huellas SHA-256 de la clave de subida y de la clave de firma de apps de Play (Play Console → Integridad de la app), más la de depuración si se prueba con builds debug.
- [ ] Cliente OAuth de Google nativo para «Continuar con Google».
- [ ] Habilitar la reverificación de sesión y comprobar que el token de la app lleva `fva` (el borrado exige un factor verificado hace < 10 min).
- [ ] Cuenta de revisión sin 2FA ni verificación de dispositivo nuevo (marcador #16).

## 5. GitHub (repo)

- [ ] Variable `CLERK_PUBLISHABLE_KEY` (`pk_live_…`): las builds firmadas fallan sin ella (`checkReleaseClerkKey`).
- [ ] Secretos `GYMLOG_KEYSTORE_BASE64`, `GYMLOG_KEYSTORE_PASSWORD`, `GYMLOG_KEY_ALIAS`, `GYMLOG_KEY_PASSWORD` (CI firma solo en `main` y tags `v*`).
- [ ] Opcional: variable `VERSION_CODE_OFFSET` (el `versionCode` de CI es `run_number + offset`; usar el offset si se renombra el workflow).
- [ ] Primer push de la rama para comprobar que el workflow de Android pasa en GitHub (JDK 21, plataforma 37).
- [ ] Guardar el keystore de subida y sus contraseñas fuera del repo, con copia de seguridad; activar Play App Signing.

## 6. Marca y recursos (puede ir en paralelo con 2–5)

- [ ] Icono final del lanzador (hoy hay una pesa provisional, diseño propio) sustituyendo `android/app/src/main/res/drawable/ic_launcher_{foreground,monochrome}.xml`.
- [ ] Icono de 512×512 con alfa para la ficha (`public/icon-512.png` no tiene canal alfa, marcador #21).
- [ ] Gráfico destacado 1024×500, 2–8 capturas de teléfono, decidir tablet (marcador #22). Detalle en `docs/play/asset-checklist.md`.
- [ ] Dominio final si difiere de `gym.chrisgoac.dev` (marcador #23; cambiar `gymlog.webBaseUrl`).

## 7. Privacidad y textos legales

- [ ] Resolver los 24 marcadores `[confirmar por el dueño]` (lista en `task-7-report.md` y en los documentos):
  1 identidad del responsable (nombre/empresa, NIF, dirección) · 2 bases legales (¿medidas corporales = datos de salud, art. 9 RGPD?) · 3 ubicaciones de encargados y garantías de transferencia (Clerk, Vercel, Neon, R2, DeepSeek/China) · 4 retención de copias/logs de proveedores · 5 edad mínima · 6 encargados = «no compartido» en Data Safety · 7 público objetivo/Familias · 8 nombre de perfil Google en Clerk · 9 medidas como «Salud» o solo «Fitness» · 10 logs técnicos de Vercel (IP) · 11 Play Integrity vía Clerk · 12 PITR/retención de Neon · 13 Coach heredado sin Pro · 14 «Reportar» en mesociclo/informe · 15 revisión de reportes · 16 cuenta de revisión en Clerk · 17 cómo obtiene Pro el revisor · 18 categoría · 19 etiquetas · 20 email de contacto (el mismo que citan `/privacy` y `/delete-account`) · 21 icono 512 con alfa · 22 capturas de tablet · 23 dominio final · 24 público objetivo/edad mínima en la Console.
- [ ] Aprobación del texto de `docs/play/privacy-policy.md` (rellenar la tabla «Aprobación» sin datos personales) y poner `PRIVACY_POLICY_APPROVED = true` en `app/privacy/status.ts` con la fecha en `PRIVACY_POLICY_LAST_UPDATED`. El test impide aprobar con marcadores pendientes. Desplegar.

## 8. Play Console (sin tocar producción)

- [ ] Crear la app `com.chrisgoac.gymlog`, ficha ES/EN (`docs/play/store-listing-{es,en}.md`).
- [ ] Productos de suscripción `gymlog_pro_monthly` y `gymlog_pro_annual`, cada uno con un plan base de renovación automática, sin pruebas ni ofertas de entrada; países y precios.
- [ ] Data Safety (`docs/play/data-safety.md`), clasificación de contenido, público objetivo, declaración de IA (`docs/play/ai-content.md`), URL de privacidad y URL de borrado de cuenta.
- [ ] Credenciales de la cuenta de revisión (`docs/play/reviewer-instructions.md`), con Pro según la decisión del marcador #17.
- [ ] Testers de licencia para las pruebas de Billing.
- [ ] Subir el AAB firmado a un track **interno o cerrado** y anotar SHA-256, versionCode/Name y commit en `docs/verification/android-release-qualification.md`.
- [ ] Informe previo al lanzamiento sin hallazgos críticos; desofuscación presente; Vitals sin crashes.
- [ ] Si la cuenta de desarrollador es personal y posterior al 13-nov-2023: closed test con ≥12 testers durante 14 días seguidos, con evidencia.

## 9. Cualificación manual

- [ ] Completar las casillas PENDING-OWNER de la sección 5 de `docs/verification/android-release-qualification.md` (API 26, API 36, Pixel, Samsung; los 6 flujos E2E; modo avión; conflicto entre dos dispositivos; aislamiento de fotos privadas; compra/restauración/revocación; informe de IA; borrado completo con `fva`; accesibilidad).
- [ ] Comprobaciones físicas heredadas: temporizador de descanso en Pixel/Samsung (notificación con la app en segundo plano y Doze), selector SAF real y copia de seguridad PWA ida y vuelta, Photo Picker/cámara, stream real de DeepSeek, R2 privado real (CORS/CSP), JWT nativos aceptados por el backend (`android-phase-02-platform-checklist.md`), cambio de cuenta sin exponer filas ni medios de la anterior.

## 10. Producción (Task 10)

- [ ] Todo lo anterior marcado y con evidencia enlazada.
- [ ] Aprobación explícita del dueño para pulsar el envío a producción, con despliegue gradual del mismo AAB.
- [ ] Tras publicar: vigilar crashes y ANR en Android Vitals.
