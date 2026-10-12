# Inventario de permisos y componentes (manifiesto release fusionado)

Fuente: `android/app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`,
generado el 2026-10-11 con:

```bash
cd android
# JAVA_HOME = JDK 17+ con javac (p. ej. el JBR de Android Studio); ANDROID_HOME = Android SDK
JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$ANDROID_HOME" ./gradlew :app:processReleaseMainManifest
```

El origen de cada línea sale de
`app/build/intermediates/manifest_merge_blame_file/release/processReleaseMainManifest/manifest-merger-blame-release-report.txt`.
Repetir esta extracción si cambian dependencias (Clerk, Billing, WorkManager, AndroidX).

`applicationId com.chrisgoac.gymlog` · `minSdk 26` · `targetSdk 36` · `versionCode 1` · `versionName 0.1.0`.

## Permisos

| Permiso | Lo declara | Para qué (visible al usuario) | ¿Se pide en tiempo de ejecución? |
|---|---|---|---|
| `android.permission.INTERNET` | app | Sincronizar tus datos, iniciar sesión, subir fotos, Coach, verificar la suscripción. | No (normal) |
| `android.permission.ACCESS_NETWORK_STATE` | app | Saber si hay conexión para sincronizar o avisar de que estás sin red. | No (normal) |
| `android.permission.POST_NOTIFICATIONS` | app | Aviso local de "Descanso terminado" del temporizador entre series. Se pide en contexto la primera vez que arranca un descanso (API 33+). Sin notificaciones push ni de marketing. | Sí (API 33+) |
| `com.android.vending.BILLING` | Play Billing 9.1.0 | Comprar y gestionar GymLog Pro con Google Play. | No |
| `android.permission.WAKE_LOCK` | WorkManager 2.11.2 | Terminar una sincronización en segundo plano. | No (normal) |
| `android.permission.RECEIVE_BOOT_COMPLETED` | WorkManager 2.11.2 | Volver a programar la sincronización pendiente tras reiniciar. | No (normal) |
| `android.permission.FOREGROUND_SERVICE` | WorkManager 2.11.2 | Declarado por la librería (`SystemForegroundService`); GymLog no ejecuta trabajos en primer plano (no llama a `setForeground`). | No |
| `android.permission.USE_BIOMETRIC` | Clerk Android 1.1.11 | Declarado por la librería de inicio de sesión; GymLog no usa biometría. | No |
| `android.permission.USE_FINGERPRINT` | androidx.biometric 1.1.0 (vía Clerk) | Igual que el anterior (permiso antiguo equivalente). | No |
| `com.chrisgoac.gymlog.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core 1.18.0 | Permiso interno con `protectionLevel=signature` para receptores dinámicos no exportados. | No |

**No se declaran** (comprobado): `CAMERA`, `READ_MEDIA_*`, `READ/WRITE_EXTERNAL_STORAGE`, ubicación, contactos,
micrófono, `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, `AD_ID`. Las fotos usan el selector de fotos del sistema y
`ACTION_IMAGE_CAPTURE` hacia un `FileProvider` privado (un test de manifiesto en `MediaUploadWorkerTest` lo vigila). El temporizador usa
`setExactAndAllowWhileIdle` solo si `canScheduleExactAlarms()` lo permite y, si no, una alarma inexacta.

Recomendación (no aplicada en esta tarea): quitar `USE_BIOMETRIC`/`USE_FINGERPRINT` con `tools:node="remove"` si
se confirma que Clerk no los necesita para las funciones que usamos, para que la ficha no muestre permisos sin uso.

## `<queries>` (visibilidad de paquetes)

| Intent | Lo declara | Para qué |
|---|---|---|
| `com.clerk.api.action.SHARED_SESSION_SYNC` | Clerk | Sincronización de sesión entre apps de Clerk (no la usa GymLog). |
| `com.android.vending.billing.InAppBillingService.BIND` | Play Billing | Conectar con Google Play para compras. |
| `com.google.android.apps.play.billingtestcompanion.BillingOverrideService.BIND` | Play Billing | Herramienta de pruebas de Google (Play Billing Lab). |

## Componentes exportados

| Componente | Lo declara | Nota |
|---|---|---|
| `com.chrisgoac.gymlog.MainActivity` (LAUNCHER) | app | Única entrada de la app. |
| `com.clerk.api.sso.SSOReceiverActivity` | Clerk | Redirección de inicio de sesión: `clerk://com.chrisgoac.gymlog.callback` y `clerk://com.chrisgoac.gymlog.oauth`. |
| `com.clerk.api.sharedsession.SharedSessionSyncProvider` | Clerk | `android:enabled="false"`; Clerk además restringe lecturas a apps con la misma firma. |
| `com.google.android.gms.auth.api.signin.RevocationBoundService` | play-services-auth (vía Clerk) | Revocación de Google Sign-In; protegido por `…signin.permission.REVOCATION_NOTIFICATION`. |
| `androidx.work.impl.diagnostics.DiagnosticsReceiver` | WorkManager | Protegido por `android.permission.DUMP`. |
| `androidx.profileinstaller.ProfileInstallReceiver` | ProfileInstaller | Protegido por `android.permission.DUMP`. |

No exportados: `RestTimerReceiver` (app), `FileProvider` `${applicationId}.media` (app), actividades de Play
Billing, Credential Manager y Google Sign-In, servicios de WorkManager y Room.

## Backup

`android:allowBackup="false"`, `dataExtractionRules` y `fullBackupContent` excluyen todos los dominios de la
copia en la nube y de la transferencia entre dispositivos.

## Librerías que tocan datos (classpath `releaseRuntimeClasspath`)

| Librería | Versión | Datos |
|---|---|---|
| `com.clerk:clerk-android-api` | 1.1.11 | Email, contraseña/código, sesión, Google Sign-In (trae `play-services-auth` 21.1.1, Credential Manager, `googleid`, `play:integrity` 1.6.0, `play-services-fido`). |
| `com.android.billingclient:billing-ktx` | 9.1.0 | Compras de suscripción. |
| `io.ktor:ktor-client-okhttp` | 3.5.2 | Transporte HTTPS hacia la API de GymLog. |
| Firebase / Crashlytics / Analytics / Sentry / anuncios | — | **Ninguno.** Solo aparecen `firebase-encoders*` y `datatransport` como dependencias transitivas de servicios de Google, sin SDK de Firebase inicializado por GymLog. |

`play-services-location` 19.0.0 aparece como dependencia transitiva; sin permisos de ubicación no puede obtener
la posición.
