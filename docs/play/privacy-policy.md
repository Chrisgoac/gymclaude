# Política de privacidad de GymLog (borrador)

- **Estado:** BORRADOR PENDIENTE DE APROBACIÓN (dueño y, si procede, asesor legal). No publicar como definitiva.
- **URL pública:** `https://gym.chrisgoac.dev/privacy` (página `app/privacy/page.tsx`). Es la misma URL que la app
  Android abre (`BuildConfig.PRIVACY_POLICY_URL` = `<gymlog.webBaseUrl>/privacy`, por defecto
  `https://gym.chrisgoac.dev/privacy`) y la que se pone en Play Console.
- **Aviso de borrador:** la página muestra "Borrador pendiente de aprobación" mientras
  `PRIVACY_POLICY_APPROVED` (en `app/privacy/status.ts`) sea `false`. Al aprobar: poner `true`, actualizar
  `PRIVACY_POLICY_LAST_UPDATED` y rellenar la sección "Aprobación" de este fichero.
- **Fuente única:** el texto publicado es el de `app/privacy/page.tsx`. Este fichero es su copia revisable. Si
  cambias uno, cambia el otro: `app/privacy/page.test.tsx` falla si los títulos de sección no coinciden.

## Texto publicado

Cómo trata GymLog (app Android y versión web) tus datos.

### Quién es el responsable

El responsable del tratamiento es el desarrollador de GymLog: [confirmar por el dueño] (nombre o razón social,
NIF y dirección postal). Puedes escribirle al correo de contacto del desarrollador que aparece en la ficha de
GymLog en Google Play.

### Qué datos tratamos

- **Cuenta:** tu email, tu identificador de usuario y, si entras con Google, los datos básicos de perfil que
  Google comparte con nuestro proveedor de inicio de sesión. Necesitas una cuenta para usar la app.
- **Entrenos:** rutinas, ejercicios personalizados, entrenamientos, series (peso, repeticiones), gimnasios,
  notas, ajustes, logros y mesociclos.
- **Medidas corporales** que tú apuntas (peso y perímetros como cintura o pecho, y las métricas que crees). Son
  opcionales.
- **Fotos** de progreso y de ejercicios que tú eliges subir. Son opcionales. La app Android las comprime y les
  quita los metadatos (EXIF), incluida la ubicación, antes de subirlas.
- **Coach con IA** (en la app Android, parte de GymLog Pro): los mensajes que escribes al Coach, sus respuestas,
  los mesociclos e informes que generas y los reportes que envías sobre respuestas incorrectas.
- **Suscripción:** el plan contratado, su estado, su fecha de fin y si se renueva. Guardamos una huella (hash)
  del justificante de compra de Google Play, no el justificante ni tus datos de pago: el pago lo gestiona Google
  Play y nunca vemos tu tarjeta.
- **Datos técnicos:** un identificador aleatorio de instalación que la app genera para sincronizar sin
  conflictos, y los datos que cualquier servidor recibe al conectarte (dirección IP, fecha y hora, tipo de
  dispositivo) en los registros de nuestros proveedores.

GymLog no usa publicidad, ni analítica de terceros, ni herramientas de seguimiento, y no pide permisos de
ubicación, contactos, cámara ni almacenamiento: las fotos se eligen con el selector del sistema o se toman con la
app de cámara del dispositivo.

### Para qué los usamos

- Darte el servicio: guardar tus datos y sincronizarlos entre tus dispositivos y la web.
- Mostrarte tu progreso, estadísticas y logros.
- Prestar las funciones de IA de GymLog Pro cuando las usas.
- Comprobar con Google Play si tienes una suscripción activa.
- Seguridad: evitar abusos (límites de uso) y resolver errores.

No vendemos tus datos ni los usamos para publicidad. Base legal: la ejecución del servicio que pides al crear tu
cuenta y, para las medidas corporales y las fotos, que tú decides apuntarlas. [confirmar por el dueño] (revisión
legal de las bases jurídicas, en particular si las medidas corporales se consideran datos de salud).

### Coach con IA

Cuando usas el Coach, generas un mesociclo o pides un informe inteligente, enviamos al proveedor de IA
(DeepSeek) tu mensaje, la conversación reciente con el Coach y un resumen de tu entrenamiento: sesiones y volumen
de la semana, ejercicios estancados, récords, grupos musculares y tus medidas más recientes. Para los mesociclos
se envían además los objetivos que eliges y la lista de ejercicios disponibles. No se envían tu email, tu
identificador de cuenta ni tus fotos.

Las respuestas las genera una IA: pueden ser incorrectas o incompletas y no son consejo médico. Ante dolor, una
lesión o dudas de salud, consulta a un profesional. Puedes reportar las respuestas del Coach desde la app; para un
mesociclo o un informe, escríbenos al correo de contacto de la ficha de Google Play.

### Con quién los compartimos

Solo con los proveedores que necesitamos para prestar el servicio, que los tratan por cuenta nuestra:

- **Clerk:** inicio de sesión y gestión de cuentas.
- **Vercel:** alojamiento de la web y del servidor de la app.
- **Neon:** base de datos (PostgreSQL) donde se guardan tus datos sincronizados.
- **Cloudflare R2:** almacenamiento de las fotos.
- **DeepSeek:** modelo de IA del Coach, los mesociclos y los informes (solo cuando usas esas funciones).
- **Google Play:** cobro y verificación de la suscripción.

No compartimos tus datos con nadie más salvo obligación legal.

### Dónde se guardan

Algunos de estos proveedores pueden tratar datos fuera del Espacio Económico Europeo (por ejemplo, en Estados
Unidos o, en el caso de DeepSeek, en China). [confirmar por el dueño] (ubicación de cada servicio y garantías de
las transferencias internacionales, como cláusulas contractuales tipo).

### Seguridad

Todas las conexiones van cifradas (HTTPS). Las fotos subidas desde la app Android se guardan en un almacenamiento
privado y solo se pueden ver con enlaces firmados que caducan en minutos. Las fotos subidas con la versión web
antigua se guardan con una dirección aleatoria difícil de adivinar. Los datos de la app en tu dispositivo no se
incluyen en las copias de seguridad en la nube de Android.

### Cuánto tiempo los conservamos

- Tus datos se conservan mientras tengas la cuenta.
- Si borras una foto, el archivo se elimina en uno o dos días. Si borras un entreno u otro elemento, se guarda una
  marca de borrado para que tus otros dispositivos también lo quiten; desaparece del todo al borrar la cuenta.
- Si borras tu cuenta, se bloquea al instante y sus datos se eliminan normalmente en minutos, con una limpieza
  final de seguridad; un reintento por fallo de algún servicio puede tardar como máximo unos días. Solo queda una
  huella anónima de que hubo un borrado, sin ningún dato tuyo.
- Al cerrar sesión en la app Android se borran del dispositivo los datos de esa cuenta.
- Las copias de seguridad de nuestros proveedores (por ejemplo, la recuperación a un momento anterior de la base
  de datos) y sus registros técnicos caducan según sus propios plazos. [confirmar por el dueño] (plazos concretos).

### Borrar tu cuenta

Puedes borrar tu cuenta y todos sus datos desde la app Android (Ajustes → Cuenta y privacidad → Eliminar cuenta)
o desde la página de borrado de cuenta (`https://gym.chrisgoac.dev/delete-account`). Borrar la cuenta **no
cancela** una suscripción a GymLog Pro: cancélala en tus suscripciones de Google Play
(`https://play.google.com/store/account/subscriptions?package=com.chrisgoac.gymlog`).

### Tus derechos

Puedes acceder a tus datos, corregirlos, exportarlos (la app exporta una copia de tus entrenos, rutinas, medidas
y ajustes), oponerte a su tratamiento, limitarlo o pedir su borrado escribiendo al correo de contacto del
desarrollador que aparece en la ficha de GymLog en Google Play, desde el correo de tu cuenta. También puedes
reclamar ante la Agencia Española de Protección de Datos (aepd.es).

### Menores

GymLog no está dirigida a menores de [confirmar por el dueño] (edad mínima) años y no tratamos a sabiendas datos
de menores de esa edad.

### Cambios en esta política

Si cambiamos esta política, actualizaremos la fecha de revisión de esta página y, si el cambio es importante, te
avisaremos en la app.

## De dónde sale cada afirmación (para el revisor)

| Afirmación | Código |
|---|---|
| Datos sincronizados (14 tablas) | `db/schema.ts`; `lib/account/deletion.ts` (cobertura del borrado, guardada por `deletion.test.ts`) |
| Medidas: peso + perímetros + personalizadas | `lib/body-metrics.ts` (`METRICAS_PREDEF`, `metricasPersonalizadas`) |
| Fotos sin EXIF, comprimidas | `android/core/data/.../media/ImageCompressor.kt` |
| Fotos Android en bucket privado, URL firmada breve | `lib/r2/client.ts` (`putPrivateObject`, `signPrivateRead`) |
| Fotos web antigua: bucket público, clave `<userId>/…/<uuid>.jpg` | `app/api/{progress,exercise}-photos/route.ts` |
| Qué recibe DeepSeek | `lib/api/v1/ai/contracts.ts` (`CoachSnapshotSchema`, `MesocycleRequestSchema`), `lib/api/v1/ai/store.ts` (`loadCoachContext`), `lib/coach-model.ts` |
| Coach web (PWA) sin Pro | `app/api/coach/route.ts` (sin `requirePro`) |
| Pro solo para IA | `requirePro` en `app/api/v1/ai/{coach,mesocycles,reports}/route.ts` |
| Solo hash del justificante | `subscription_entitlements.purchase_token_hash`, `billing_events.purchase_token_hash` |
| Id de instalación aleatorio | `AppContainer.installationDeviceId` (`"android-" + UUID`), `sync_mutations`/`last_device_id` |
| Sin copia en la nube | `android/app/src/main/res/xml/data_extraction_rules.xml`, `allowBackup=false` |
| Borrado de foto ≥24 h + cron diario | `MEDIA_DELETION_DELAY_MS` (`lib/api/v1/media/deletions.ts`), `vercel.json` 04:00 UTC |
| Borrado de cuenta y limpieza final | `lib/account/deletion.ts`, cron 04:30 UTC, `account_deletion_jobs` (solo `user_id_hash` al terminar) |
| Cierre de sesión borra datos locales | `SignOutUseCase` (`android/core/domain/.../BackgroundSync.kt`) |
| Sin analítica/anuncios | dependencias de `package.json` y `android/gradle/libs.versions.toml` (ver `permissions-inventory.md`) |

## Aprobación

| Campo | Valor |
|---|---|
| Fecha de aprobación | |
| Rol de quien aprueba (p. ej. "dueño", "asesor legal") | |
| Versión aprobada (`PRIVACY_POLICY_LAST_UPDATED`) | |

No escribir nombres, emails ni otros datos personales en esta tabla.
