# Seguridad de los datos (Play Console → Contenido de la app → Seguridad de los datos)

Borrador para rellenar el formulario. Derivado del código de la rama `feat/android-release` (ver
`privacy-policy.md`, tabla "De dónde sale cada afirmación", y `permissions-inventory.md`). Pendiente de
aprobación del dueño junto con la política.

Definiciones de Play que se aplican:
- **Recogido** = sale del dispositivo hacia nosotros o un proveedor nuestro.
- **Compartido** = se transfiere a un tercero. Los **proveedores que tratan los datos por nuestra cuenta**
  (Clerk, Vercel, Neon, Cloudflare R2, DeepSeek) **no cuentan como compartir** según Play, siempre que haya
  contrato de encargo de tratamiento. [confirmar por el dueño] (que los términos de cada proveedor, en
  especial DeepSeek, lo cubren; si DeepSeek no actúa como encargado, marcar "Compartido" para los datos del Coach).
- Los datos que solo se quedan en el dispositivo (base de datos local, temporizador de descanso) no se declaran.

## 1. Recogida y seguridad (preguntas generales)

| Pregunta | Respuesta | Por qué |
|---|---|---|
| ¿Tu app recoge o comparte alguno de los tipos de datos requeridos? | Sí | Cuenta, entrenos, medidas, fotos, Coach, suscripción. |
| ¿Todos los datos de usuario recogidos están cifrados en tránsito? | Sí | API y web solo por HTTPS (`gymlog.apiBaseUrl` = `https://…`; sin `usesCleartextTraffic`, targetSdk 36 lo bloquea por defecto); Clerk, Google Play y R2 (URLs firmadas `https`) también. |
| ¿Ofreces una forma de que los usuarios pidan que se borren sus datos? | Sí | En la app (Ajustes → Cuenta y privacidad → Eliminar cuenta) y en la web `https://gym.chrisgoac.dev/delete-account`. |
| URL de borrado de cuenta (campo del formulario) | `https://gym.chrisgoac.dev/delete-account` | Igual que `BuildConfig.ACCOUNT_DELETION_URL`. |
| ¿Se pueden borrar algunos datos sin borrar la cuenta? | Sí | El usuario borra entrenos, medidas, fotos, mensajes del Coach (tombstone sincronizado; las fotos se eliminan de R2 tras ≥24 h). |
| Revisión de seguridad independiente (MASA) | No | No hecha. |
| Compromiso con la política de Familias | No aplica | La app no está dirigida a menores. [confirmar por el dueño] (público objetivo y edad mínima). |

## 2. Tipos de datos

Leyenda: R = recogido, C = compartido, Obl. = obligatorio / Opc. = opcional (el usuario decide), Efímero = no.
Ningún dato se usa para publicidad, marketing ni personalización de anuncios. No hay analítica.

| Categoría Play | Tipo Play | R | C | Obl./Opc. | Finalidades Play | Dato real en GymLog |
|---|---|---|---|---|---|---|
| Información personal | Dirección de correo electrónico | Sí | No | Obl. | Funcionalidad de la app; Gestión de la cuenta | Email de la cuenta (Clerk; código por email, contraseña o Google). |
| Información personal | Nombre | Sí | No | Opc. | Gestión de la cuenta | Solo si entra con Google: Clerk guarda el nombre del perfil de Google. GymLog no lo pide ni lo usa. [confirmar por el dueño] (si la instancia de Clerk guarda nombre/foto de Google). |
| Información personal | ID de usuario | Sí | No | Obl. | Funcionalidad de la app; Gestión de la cuenta | Id de usuario de Clerk; clave de todas las filas en Neon. |
| Salud y forma física | Información de forma física | Sí | No | Obl. (para usar la app) | Funcionalidad de la app | Rutinas, entrenamientos, series (peso/reps), mesociclos, logros. |
| Salud y forma física | Información de salud | Sí | No | Opc. | Funcionalidad de la app | Peso corporal y perímetros (y métricas personalizadas). Declaración conservadora: Play define "salud" de forma amplia. [confirmar por el dueño] (mantenerlo en "salud" o solo en "forma física"). |
| Fotos y vídeos | Fotos | Sí | No | Opc. | Funcionalidad de la app | Fotos de progreso y de ejercicios (JPEG recomprimido, sin EXIF). |
| Información financiera | Historial de compras | Sí | No | Opc. | Funcionalidad de la app; Gestión de la cuenta | Producto (`gymlog_pro_monthly`/`gymlog_pro_annual`), estado, caducidad, renovación y hash del token de compra. Sin datos de pago. |
| Mensajes | Otros mensajes en la app | Sí | No | Opc. | Funcionalidad de la app | Mensajes al Coach y respuestas (tabla sincronizada `coach_messages`, `ai_responses`). Alternativa válida: "Actividad en la app → Otro contenido generado por el usuario"; usar una sola. |
| Actividad en la app | Otro contenido generado por el usuario | Sí | No | Opc. | Funcionalidad de la app | Notas de entrenos/rutinas, ejercicios personalizados, gimnasios, ajustes, reportes de respuestas de IA (categoría; la API admite contexto opcional ≤500 caracteres, Android no lo envía). |
| ID de dispositivo u otros IDs | ID de dispositivo u otros IDs | Sí | No | Obl. | Funcionalidad de la app | Id aleatorio de instalación (`android-<UUID>`) enviado en cada sincronización y guardado como `last_device_id`. No es el Android ID ni el ID de publicidad. |
| Información y rendimiento de la app | Registros de fallos / Diagnóstico | No | No | — | — | No hay SDK de fallos ni analítica. Los registros del servidor (Vercel) no incluyen contenido del usuario. [confirmar por el dueño] (si Play exige declarar los registros técnicos de Vercel con IP). |
| Ubicación | Aproximada / precisa | No | No | — | — | Sin permisos de ubicación; las fotos se suben sin EXIF. `play-services-location` llega como dependencia transitiva de Clerk pero sin permiso no puede leer ubicación. |
| Contactos, calendario, audio, archivos, navegación web, mensajes SMS/email | — | No | No | — | — | No se usan. El selector de fotos del sistema no da acceso a archivos. |

### Datos que tratan las librerías del SDK (revisar con su documentación)

- **Clerk Android 1.1.11**: además del email/ID, Clerk recibe datos técnicos de la sesión (IP, user agent) e
  incluye `com.google.android.play:integrity` (atestación del dispositivo). [confirmar por el dueño] (si la
  instancia de Clerk usa atestación de dispositivo; si sí, revisar si hay que declarar "ID de dispositivo" también
  por Clerk).
- **Google Play Billing 9.1.0**: la compra la procesa Google; GymLog solo recibe el token y el estado. El
  `obfuscatedAccountId` enviado a Google es `sha256(userId)` (no reversible).
- **Ktor/OkHttp**: solo transporte. El código de GymLog no instala registro de peticiones (ni Ktor `Logging` ni
  `HttpLoggingInterceptor`; este último llega como dependencia transitiva de Clerk).

## 3. Finalidad y tratamiento por tipo

| Pregunta Play (por tipo) | Respuesta para todos los tipos recogidos |
|---|---|
| ¿Se trata de forma efímera? | No (se guardan en Neon/R2 para sincronizar). Excepción: el resumen de entreno que va a DeepSeek es por petición, pero las respuestas sí se guardan. |
| ¿Obligatorio u opcional? | Ver columna Obl./Opc. |
| Finalidades | Solo "Funcionalidad de la app" y "Gestión de la cuenta" (más "Seguridad, cumplimiento y prevención de fraudes" para el ID de usuario por los límites de uso por usuario en `api_rate_buckets`). Nunca Analítica, Comunicaciones del desarrollador, Publicidad ni Personalización. |

## 4. Borrado y conservación (coherente con `privacy-policy.md`)

- Borrado de cuenta (`lib/account/deletion.ts`): bloqueo inmediato → `storage` (R2) → `database` (14 tablas
  sincronizadas + sync, entitlements, IA, reportes, eventos de facturación de sus tokens, cubos de límites) →
  `storage_sweep` (segundo listado de R2) → `identity` (Clerk) → `database_sweep` (barrido final de Postgres y
  R2, ≥120 s después de la petición; lo ejecuta el cron diario de las 04:30 UTC, así que puede llegar hasta ~24 h
  más tarde). Los fallos se reintentan cada día sin límite fijo. Al terminar queda solo
  `account_deletion_jobs.user_id_hash` (SHA-256 sin clave del id de Clerk: seudónimo, no anónimo).
- La suscripción de Google Play **no** se cancela al borrar la cuenta (se avisa en app y web).
- Copias de Neon (point-in-time recovery), versiones/ciclo de vida de R2 y registros de Vercel/Clerk: según cada
  proveedor. [confirmar por el dueño] (ventana de PITR de Neon y retención de registros).
