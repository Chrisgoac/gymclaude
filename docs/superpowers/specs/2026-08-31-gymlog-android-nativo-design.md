# GymLog Android nativo — Diseño

- **Fecha:** 2026-08-31
- **Estado:** aprobado
- **Plataforma:** Android nativo
- **Distribución:** pública en Google Play

## 1. Resumen

GymLog se reescribirá como una aplicación Android nativa con Kotlin y Jetpack Compose. No será un WebView, una PWA empaquetada ni una solución multiplataforma. La aplicación conservará la funcionalidad actual, añadirá edición completa de entrenamientos históricos y reutilizará la plataforma cloud existente tras convertir sus rutas de Next.js en una API móvil robusta y versionada.

La experiencia será local-first: Room será la fuente inmediata de verdad del dispositivo, la interfaz leerá exclusivamente de la base local y WorkManager sincronizará en segundo plano con Neon. El usuario deberá autenticarse en el primer arranque; después podrá entrenar y consultar sus datos sin conexión.

La primera publicación de producción alcanzará paridad funcional completa con la PWA. No habrá lanzamientos públicos parciales, aunque existirán compilaciones internas y versiones privadas exigidas para pruebas y revisión de Google Play.

## 2. Objetivos

1. Ofrecer una aplicación Android realmente nativa, rápida y usable con una mano durante el entrenamiento.
2. Mantener el funcionamiento offline de entrenamientos, rutinas, histórico, ejercicios, progreso, cuerpo y logros.
3. Restaurar automáticamente los datos cloud de la cuenta actual del usuario al iniciar sesión con el mismo Clerk.
4. Permitir que cualquier persona cree una cuenta y mantenga sus datos totalmente aislados.
5. Alcanzar paridad funcional con la PWA y añadir edición completa del histórico.
6. Publicar la aplicación en Google Play mediante un AAB firmado, orientado a API 36.
7. Monetizar únicamente las funciones de IA mediante GymLog Pro, sin anuncios ni bloqueo de los datos del usuario.

## 3. No objetivos

- iOS, Kotlin Multiplatform, React Native, Flutter o una capa de abstracción futura para iPhone.
- Una conversión automática del JSX/CSS existente.
- Reescribir Neon, Drizzle, R2 o DeepSeek sin una necesidad concreta.
- Migrar datos que solo existan en IndexedDB de la PWA. La migración cubre los datos ya sincronizados en la nube.
- Funciones sociales, entrenadores, equipos, cuentas de gimnasio o roles administrativos.
- Publicidad, venta de datos o analítica publicitaria.
- Notificaciones promocionales o push remoto en la primera versión. Solo habrá notificaciones locales funcionales, como el temporizador de descanso.
- Un lanzamiento público por fases. Las fases de desarrollo no se expondrán como versiones incompletas de producción.

## 4. Decisiones aprobadas

| Tema | Decisión |
|---|---|
| Cliente | Kotlin + Jetpack Compose, completamente nativo |
| Compatibilidad | `minSdk` 26; `targetSdk` 36 |
| Interfaz | Identidad **Hierro nativo**: brutalismo cálido, bordes fuertes, naranja de seguridad y tipografía contundente |
| Navegación | Cinco destinos: Entrenar, Rutinas, Ejercicios, Progreso e Historial |
| Datos locales | Room como fuente de verdad; DataStore para preferencias no sincronizadas |
| Background | WorkManager para sincronización persistente y reintentos |
| Backend | Next.js conservado como backend; API `/api/v1` móvil y versionada |
| Nube | Neon/Postgres + Drizzle, Cloudflare R2 y DeepSeek |
| Identidad | Clerk Android nativo; misma instancia y mismos `userId` actuales |
| Primer uso | Login online obligatorio; uso offline posterior |
| Distribución | Pública en Google Play mediante Android App Bundle |
| Monetización | Freemium sin anuncios; IA reservada a GymLog Pro |
| Histórico | Edición completa de fecha, gimnasio, notas, ejercicios, orden y series |

## 5. Arquitectura general

### 5.1 Repositorio

El proyecto Android vivirá en `android/` dentro del repositorio actual. El backend Next.js permanecerá en la raíz durante la migración y después del lanzamiento.

Módulos Android propuestos:

- `app`: composición de la aplicación, navegación global y configuración de builds.
- `core:model`: modelos de dominio y contratos sin dependencias Android.
- `core:database`: Room, migraciones, DAOs, outbox y base por usuario.
- `core:network`: cliente HTTP, DTOs, streaming, autenticación y errores de transporte.
- `core:data`: repositorios offline-first y coordinación local/remoto.
- `core:domain`: reglas, casos de uso y cálculos puros.
- `core:designsystem`: tema Hierro nativo, componentes, iconos, tipografías y accesibilidad.
- `core:testing`: fixtures, dobles y utilidades compartidas.
- `feature:auth`, `feature:train`, `feature:routines`, `feature:exercises`, `feature:progress`, `feature:history`, `feature:body`, `feature:achievements`, `feature:coach` y `feature:settings`.

Los módulos de feature no accederán directamente a Room, HTTP, Clerk o Play Billing. Consumirán casos de uso e interfaces de repositorio. Esto mantiene separadas las pantallas, el dominio y la infraestructura.

### 5.2 Flujo de estado

1. Una pantalla Compose envía eventos a su `ViewModel`.
2. El `ViewModel` ejecuta un caso de uso.
3. El repositorio escribe en Room.
4. Room emite el nuevo estado mediante `Flow`.
5. Compose redibuja la pantalla.
6. Si el cambio es sincronizable, la misma transacción de Room añade una operación a la outbox.
7. WorkManager envía la outbox y aplica después la respuesta remota en Room.

La UI nunca esperará una respuesta de red para confirmar una mutación local ordinaria.

### 5.3 Backend conservado

Next.js seguirá ofreciendo:

- API móvil `/api/v1`.
- Web mínima de privacidad y solicitud externa de borrado de cuenta.
- Integraciones server-only con Neon, R2, DeepSeek y Google Play Developer API.
- Compatibilidad temporal con los endpoints de la PWA durante la transición.

La experiencia dentro de Android será nativa; conservar Next.js como servidor no introduce UI web en la APK.

## 6. Diseño de interfaz y navegación

### 6.1 Sistema visual Hierro nativo

La identidad actual se trasladará a Compose, no se copiará literalmente desde CSS. El sistema incluirá:

- Fondo cálido tipo hormigón, superficies claras y variante oscura.
- Bordes negros fuertes, sombras duras y naranja de seguridad como acento.
- Tipografía condensada para títulos y métricas; tipografía legible para texto y formularios.
- Componentes Compose con estados pressed, focused, disabled, error y loading.
- Áreas táctiles mínimas, soporte de TalkBack, escala de fuente y contraste comprobado.
- Insets del sistema, navegación gestual, orientación vertical y adaptación razonable a tablet/foldable sin crear una experiencia distinta.

### 6.2 Navegación principal

La barra inferior mantiene cinco destinos:

1. **Entrenar**
2. **Rutinas**
3. **Ejercicios**
4. **Progreso**
5. **Historial**

Destinos secundarios desde cabecera o tarjetas:

- Coach Pro
- Cuerpo
- Logros
- Ajustes, cuenta y suscripción

Cada destino principal tendrá su propio grafo de navegación. Una sesión activa conservará estado aunque la aplicación pase a segundo plano o el proceso se recree.

### 6.3 Inventario funcional de la versión pública

#### Entrenar

- Inicio con siguiente rutina, resumen semanal, racha y acceso al Coach.
- Selección de gimnasio.
- Entreno desde rutina o libre.
- Registro rápido de peso y repeticiones, series objetivo, datos anteriores y sugerencia de progresión.
- Añadir, quitar y reordenar ejercicios y series.
- Temporizador de descanso con notificación local, vibración/háptica y continuidad en segundo plano.
- Finalización y resumen.

#### Rutinas y mesociclos

- Listar, crear, editar, archivar, borrar y ordenar rutinas.
- Añadir y ordenar ejercicios, objetivos, rangos de repeticiones, descansos y notas.
- Iniciar una rutina.
- Generar mesociclos con IA para usuarios Pro.
- Revisar, guardar y consultar la progresión semanal de un mesociclo.

#### Ejercicios

- Catálogo precargado, búsqueda y filtros.
- Detalle con descripción, ejecución, vídeo y fotos.
- Crear, editar y borrar ejercicios personalizados.
- Cámara y Android Photo Picker para fotos propias.

El catálogo global se empaquetará como un asset versionado en la APK para funcionar offline. Las filas globales no se sincronizarán como datos de usuario; los ejercicios personalizados sí.

#### Progreso

- Selector de periodo y gimnasio.
- Resumen semanal y métricas principales.
- Progreso por ejercicio, PRs, 1RM estimado y volumen.
- Equilibrio muscular, estancamientos y sugerencias.
- Informe inteligente para usuarios Pro.

#### Historial

- Lista filtrable de entrenamientos.
- Detalle completo de cada sesión.
- Cambio de gimnasio.
- Edición completa.
- Eliminación con confirmación.

#### Cuerpo y fotos

- Registro de peso y medidas predefinidas o personalizadas.
- Gráficas y entradas históricas.
- Captura/selección de fotos, galería privada y comparación.

#### Logros

- Racha semanal.
- Hitos desbloqueados.
- Récords personales.
- Reconciliación determinista contra los datos actuales.

#### Coach Pro

- Un hilo persistente por usuario.
- Respuestas en streaming.
- Prompts sugeridos.
- Snapshot calculado localmente con estadísticas pertinentes.
- Reporte de respuesta problemática desde la propia pantalla.

#### Ajustes

- Cuenta, cerrar sesión y borrar cuenta.
- Estado de sincronización.
- Gestión de gimnasios y orden de rutinas.
- Unidades, tema, objetivos y reglas de progresión.
- Exportar/importar backup mediante Storage Access Framework.
- Estado de GymLog Pro, compra y enlace para gestionar la suscripción.
- Política de privacidad y soporte.

## 7. Edición completa del histórico

### 7.1 Campos editables

Desde el detalle de un entrenamiento terminado, el usuario podrá modificar:

- Fecha y hora.
- Gimnasio.
- Notas.
- Ejercicios incluidos.
- Orden de ejercicios.
- Series de cada ejercicio.
- Orden de series.
- Peso, repeticiones y marca de calentamiento.

### 7.2 Semántica de guardado

- Al entrar en edición se crea un estado de trabajo aislado.
- **Cancelar** descarta íntegramente ese estado.
- **Guardar** valida y aplica todos los cambios en una sola transacción Room.
- Añadir, quitar o reordenar elementos conserva identificadores estables siempre que el elemento siga representando el mismo registro.
- Los borrados generan tombstones para impedir reapariciones desde otro dispositivo.
- El guardado produce una única mutación lógica de outbox, aunque afecte a varias tablas.

### 7.3 Efectos derivados

Después del guardado se invalidarán y recalcularán:

- PRs y 1RM estimado.
- Volumen y equilibrio muscular.
- Gráficas por ejercicio.
- Rachas y resumen semanal.
- Detección de estancamiento y sugerencias.
- Logros cuya evidencia haya cambiado.
- Snapshot que recibirá el Coach en la siguiente consulta.

Los logros se reconciliarán con la verdad actual. Si una corrección elimina la evidencia de un logro, el desbloqueo se retirará mediante tombstone; si vuelve a cumplirse, se desbloqueará de nuevo de forma idempotente.

## 8. Datos y contratos

### 8.1 Entidades sincronizadas

- Exercise personalizado
- Routine
- RoutineExercise
- WorkoutSession
- LoggedExercise
- LoggedSet
- Gym
- ExercisePhoto
- UserSetting
- CoachMessage
- BodyMetric
- ProgressPhoto
- Mesocycle
- Achievement

Metadatos comunes: `id`, `userId`, `updatedAt`, `deletedAt` y `serverVersion`. Los IDs se generan en el cliente y permanecen estables offline.

Entidades controladas exclusivamente por el servidor y expuestas mediante endpoints específicos, no mediante el push bidireccional:

- `SubscriptionEntitlement`
- `AIReport`
- `ReportedAIResponse`

El cliente nunca podrá crear o modificar derechos Pro a través del protocolo de sincronización.

### 8.2 Corrección del esquema existente

Antes de conectar Android se unificarán Room, DTOs y Postgres. El servidor incorporará los campos locales que hoy no están representados:

- `Exercise.descripcion`
- `Exercise.execution`
- `Exercise.incrementoKg`
- `RoutineExercise.repsObjetivoMin`

Los DTO se validarán en la frontera HTTP. Los nombres de tabla desconocidos, tipos incorrectos o campos fuera de contrato serán errores explícitos; nunca se ignorarán silenciosamente.

### 8.3 Preferencias locales

DataStore guardará únicamente estado no sincronizable, como el último filtro local, flags de onboarding, preferencias de notificación y metadatos del dispositivo. Los ajustes que deban converger entre dispositivos seguirán siendo filas de `UserSetting`.

### 8.4 Datos por usuario

Cada cuenta tendrá una base Room físicamente separada. El nombre del fichero se derivará de un hash no reversible del `userId` y solo se mantendrá abierta la base de la sesión activa. No se reutilizará una misma base entre cuentas. Los tokens se almacenarán con las protecciones del SDK y Android Keystore.

## 9. API móvil `/api/v1`

### 9.1 Principios

- HTTPS y `Authorization: Bearer <token Clerk>`.
- Aislamiento por `userId` obtenido del token, nunca del cuerpo enviado por el cliente.
- DTOs versionados y validados.
- Límites de tamaño, paginación y rate limiting.
- Escrituras idempotentes.
- Transacciones para mutaciones multi-entidad.
- Errores JSON estables con código de máquina, mensaje presentable y carácter retryable/no retryable.

### 9.2 Superficie inicial

- `POST /api/v1/sync/push`
- `GET /api/v1/sync/pull?cursor=...`
- `POST /api/v1/media/exercises`
- `DELETE /api/v1/media/exercises/:id`
- `POST /api/v1/media/progress`
- `DELETE /api/v1/media/progress/:id`
- `POST /api/v1/ai/coach`
- `POST /api/v1/ai/mesocycles`
- `POST /api/v1/ai/reports`
- `POST /api/v1/ai/responses/:id/report`
- `POST /api/v1/billing/verify`
- `GET /api/v1/billing/entitlement`
- `DELETE /api/v1/account`

Las notificaciones de Google Play Billing entrarán por un webhook server-to-server independiente de la API móvil.

## 10. Sincronización offline-first

### 10.1 Fuente de verdad

Room es la fuente exclusiva de lectura para la interfaz. Postgres es la copia cloud autoritativa entre dispositivos, pero nunca bloquea una pantalla mientras una copia local válida esté disponible.

### 10.2 Outbox

Cada mutación local y su entrada de outbox se escriben en la misma transacción. Una entrada incluye:

- `mutationId` único.
- Usuario y dispositivo.
- Operaciones ordenadas.
- Fecha lógica de modificación.
- Revisión cloud conocida de cada fila.
- Número de intentos y último error.

El servidor registra `mutationId` y responde idempotentemente a reintentos. En la primera versión no se purgarán deduplicaciones ni tombstones; el volumen esperado es pequeño y se prioriza impedir duplicados o resurrecciones.

### 10.3 Push y pull

- Push envía lotes acotados y conserva el orden de mutaciones lógicas.
- Una mutación de edición histórica se aplica completa o no se aplica.
- Pull usa un cursor opaco basado en una secuencia monotónica del servidor, no un timestamp como cursor.
- Pull está paginado y devuelve `hasMore` junto al siguiente cursor.
- El cliente actualiza el cursor únicamente después de aplicar la página completa en Room.

### 10.4 Conflictos

La primera versión conserva LWW por registro porque cada usuario edita principalmente sus propios datos y las entidades finas reducen colisiones. La resolución será determinista mediante la tupla `updatedAt`, `deviceId` y `mutationId`. El servidor rechazará o normalizará relojes de cliente irrazonablemente futuros.

La revisión cloud conocida permite detectar concurrencia y registrar el conflicto para diagnóstico. No se mostrará un diálogo al usuario en la primera versión; la resolución automática se aplicará igual en Kotlin y TypeScript mediante fixtures compartidos.

### 10.5 Disparadores

La sincronización se solicita:

- Después del login y bootstrap inicial.
- Al arrancar la app.
- Al volver a primer plano.
- Cuando reaparece conectividad.
- Tras una mutación local, con debounce.
- Periódicamente mediante WorkManager, respetando batería y restricciones del sistema.

Solo una sincronización podrá ejecutarse a la vez por usuario.

### 10.6 Bootstrap y restauración

El primer login requiere conexión. Con la misma instancia de Clerk, la cuenta existente conserva el mismo `userId`; la app crea su Room vacío, descarga todas las páginas cloud, aplica el catálogo incluido en la APK y solo entonces abre la navegación principal.

Si el bootstrap se interrumpe, se reanuda desde un checkpoint seguro. Nunca se presenta una restauración parcial como completada.

## 11. Fotos y archivos

- Se usará Android Photo Picker o cámara, sin permiso general permanente sobre la galería.
- Las imágenes se comprimirán antes de subir y mantendrán orientación y calidad suficientes.
- Una foto capturada offline se guardará temporalmente en almacenamiento privado de la app y entrará en una cola durable.
- R2 pasará de URLs públicas permanentes a objetos privados con URLs de lectura firmadas y breves.
- La fila sincronizada guardará el object key estable, no la URL firmada. La URL se obtendrá bajo demanda y podrá cachearse solo hasta su expiración.
- Las miniaturas ya consultadas podrán cachearse en almacenamiento privado para lectura offline; una foto nunca descargada se mostrará como no disponible hasta recuperar conexión.
- El backend validará tipo, tamaño, propiedad y namespace del objeto.
- La eliminación de una foto marcará primero la fila; un proceso idempotente retirará después el objeto R2.
- Los temporales locales se borrarán después de confirmar la subida o al borrar/cerrar la cuenta.

## 12. Autenticación y sesión

- Se utilizará el SDK Android nativo de Clerk con la instancia de producción actual.
- Login y registro admiten email y Google según la configuración existente.
- El flujo se personalizará visualmente sin reimplementar criptografía, sesiones o recuperación de cuenta.
- La Native API de Clerk se configurará con el namespace, package y callbacks verificados de la aplicación. Como abre un acceso público sin los mismos desafíos CAPTCHA del navegador, se probarán específicamente rate limiting, registro automatizado y recuperación abusiva.
- Los endpoints verificarán el bearer token en servidor.
- Una sesión caducada pausa la red y solicita reautenticación; no borra datos locales automáticamente.
- Cerrar sesión exige resolver cambios pendientes: sincronizar o confirmar explícitamente su descarte. Después se borra la base local, cachés y credenciales de esa cuenta.

## 13. GymLog Pro y Google Play Billing

### 13.1 Gratis

- Entrenamientos y temporizador.
- Rutinas y ejercicios.
- Histórico y edición completa.
- Progreso, PRs, cuerpo y logros.
- Sincronización cloud.
- Fotos y backup.

Los datos creados por el usuario nunca quedan bloqueados por una suscripción.

### 13.2 Pro

- Chat con Coach IA.
- Generación de mesociclos.
- Informes inteligentes.

El uso normal se percibirá como disponible sin contador visible. El servidor aplicará límites operativos por minuto y por día, configurables sin actualizar la APK, para impedir automatización o abuso.

### 13.3 Compra y derechos

- Habrá planes mensual y anual; la primera versión no añade prueba gratuita ni promociones.
- Precios y disponibilidad regional se gestionan en Play Console.
- La app usa Google Play Billing para iniciar y restaurar compras.
- El backend verifica el token de compra y mantiene `SubscriptionEntitlement`.
- Las notificaciones en tiempo real de Google actualizan renovaciones, cancelaciones, reembolsos y expiraciones.
- Las rutas IA comprueban el derecho Pro en servidor; el estado cacheado de la APK solo controla presentación.
- Ajustes enlaza a la gestión de suscripciones de Google Play.

## 14. IA y seguridad del contenido

- DeepSeek se invoca únicamente desde el servidor.
- El snapshot del Coach contiene solo datos necesarios para responder; no incluye fotos.
- La UI declara que el Coach no sustituye asesoramiento médico ni diagnostica lesiones.
- Respuestas y errores parciales se presentan de forma clara; reintentar no duplica mensajes del usuario.
- Cada respuesta del Coach puede reportarse dentro de la app.
- Los reportes contienen identificadores, categoría y el mínimo texto necesario para revisión; no se envían a terceros adicionales.
- El backend aplica rate limiting, validación de tamaño y moderación de entradas/salidas exigida para publicación.

## 15. Seguridad, privacidad y cuenta

- Secretos de Neon, R2, DeepSeek y Google Play permanecen en el servidor.
- Todas las consultas cloud se filtran por el `userId` autenticado.
- R2 usa objetos privados y URLs firmadas.
- La base y temporales residen en almacenamiento privado; claves y tokens usan Android Keystore o las protecciones del SDK.
- Los backups exportados son una acción explícita del usuario mediante Storage Access Framework.
- No se integra una plataforma de publicidad ni analítica de marketing en la primera versión.
- La aplicación incluirá política de privacidad, declaración Data Safety y divulgaciones necesarias para fotos, datos de actividad, cuenta e IA.
- Ajustes ofrece borrado total de cuenta.
- Una página web pública ofrece la misma solicitud fuera de la app.
- El borrado elimina datos Neon, objetos R2, mensajes, reportes y el vínculo de entitlement. El historial financiero que Google Play deba conservar queda bajo su política.
- Si hay una suscripción activa, el flujo explica que borrar la cuenta no sustituye la gestión de la suscripción y ofrece el enlace correspondiente antes de confirmar.

## 16. Gestión de errores

### 16.1 Red y sincronización

- Errores transitorios reintentan con backoff exponencial y jitter.
- `401/403` pausa la sincronización hasta renovar sesión o reautenticar.
- Datos inválidos no reintentan indefinidamente: la mutación se pone en cuarentena, el resto continúa y se ofrece diagnóstico accionable.
- Un cursor inválido activa un nuevo bootstrap controlado, sin borrar la base válida hasta completar la sustitución.
- El usuario ve estados comprensibles: sincronizado, cambios pendientes, offline, requiere sesión y error que necesita acción.

### 16.2 Escrituras locales

- Un fallo de Room revierte tanto el dato como la outbox.
- Validaciones de peso, repeticiones, fechas, orden y referencias se ejecutan antes de la transacción.
- Las operaciones destructivas requieren confirmación.

### 16.3 Fotos

- Captura offline permanece pendiente.
- Un fallo de compresión o formato se muestra antes de encolar.
- Un fallo de subida reintenta; un rechazo definitivo conserva el original hasta que el usuario corrija o descarte.

### 16.4 IA y Billing

- Coach y compras muestran explícitamente que requieren red.
- Un stream interrumpido conserva el mensaje del usuario y marca la respuesta como incompleta.
- La app nunca concede Pro solo porque el cliente informe de una compra; espera verificación server-side.

## 17. Estrategia de pruebas

### 17.1 Unitarias Kotlin/JVM

- Progresión y sugerencias de peso.
- PRs, 1RM, volumen, equilibrio, rachas e insights.
- Fechas, periodos y unidades.
- Evaluación/reconciliación de logros.
- Resolución de conflictos, tombstones e idempotencia.
- Validación de edición histórica.

Los tests TypeScript existentes se usarán como especificación. Los mismos fixtures de entrada/salida se ejecutarán en Kotlin y TypeScript para detectar divergencias.

### 17.2 Base de datos y contratos

- Migraciones Room desde cada versión publicada internamente.
- Transacciones de outbox.
- Bootstrap interrumpido y reanudado.
- Paridad de todos los campos entre Room, JSON validado y Drizzle.
- Push/pull paginado, duplicado, fuera de orden y con conflictos.
- Aislamiento entre usuarios.

### 17.3 Compose e instrumentadas

- Navegación y restauración de estado.
- Crear/finalizar entrenamiento.
- Temporizador en foreground/background y recreación de proceso.
- Edición histórica completa, cancelar y guardar.
- Rutinas, catálogo y fotos.
- Compra/restauración/cancelación de Pro con entorno de pruebas.
- Borrado de cuenta.
- TalkBack, tamaño de fuente, contraste y áreas táctiles.

### 17.4 End-to-end críticos

1. Login con la cuenta existente y restauración cloud completa.
2. Entreno offline, cierre/reapertura, finalización y sincronización posterior.
3. Edición histórica offline y convergencia en un segundo dispositivo.
4. Foto offline, subida al recuperar red y lectura mediante URL firmada.
5. Compra Pro, Coach, restauración y revocación.
6. Borrado total de cuenta y ausencia de acceso posterior.

### 17.5 Calidad de publicación

- Lint, análisis estático, unit tests y contract tests en CI.
- Tests instrumentados en emuladores API 26 y API 36 y al menos dos dispositivos físicos: un Pixel de referencia y un Samsung con personalización del fabricante.
- Build release con minificación, símbolos y configuración de firma reproducible.
- Informe previo al lanzamiento de Google Play sin errores críticos de estabilidad, compatibilidad, rendimiento o accesibilidad.
- Revisión manual de Data Safety, política de privacidad, permisos y flujo de cuenta.

## 18. Publicación

- Artefacto de producción: Android App Bundle firmado.
- `targetSdk` 36 y `minSdk` 26.
- Misma funcionalidad para todos los usuarios de producción; Pro solo cambia el acceso a IA.
- El mismo AAB se desplegará gradualmente para limitar impacto, sin crear ediciones funcionales distintas.
- Play Console generará APK optimizados por dispositivo.
- Se proporcionará una cuenta de prueba separada para el informe previo al lanzamiento.
- Si la cuenta personal de Play Console fue creada después del 13 de noviembre de 2023, se completará el closed test obligatorio con al menos 12 testers durante 14 días antes de solicitar producción. Esas versiones son privadas y no contradicen la decisión de un único lanzamiento público.
- Después del lanzamiento se vigilarán crashes y ANR mediante Android Vitals. No se incluirá tracking publicitario.

## 19. Migración y convivencia

1. Corregir el esquema cloud y desplegar migraciones compatibles hacia atrás.
2. Añadir `/api/v1` sin retirar aún los endpoints usados por la PWA.
3. Crear y validar el cliente Android contra cuentas de prueba.
4. Verificar la cuenta existente y comparar recuentos/fixtures de todas las entidades.
5. Ejecutar pruebas privadas y requisitos de Play.
6. Publicar el único lanzamiento de producción.

La PWA podrá seguir usando los endpoints antiguos durante la transición. La APK descargará solo la copia cloud; no leerá IndexedDB ni intentará importar datos locales automáticamente.

## 20. Criterios de aceptación

El diseño se considera implementado cuando:

1. Todas las funciones enumeradas en la sección 6 están disponibles con UI Compose nativa.
2. No existe WebView para pantallas del producto.
3. La cuenta existente restaura todas sus entidades cloud sin pérdida de campos.
4. El usuario puede completar y editar entrenamientos totalmente offline.
5. La edición histórica recalcula y sincroniza todos los derivados definidos.
6. Dos dispositivos convergen después de cambios offline y reintentos duplicados.
7. Fotos, tokens y secretos cumplen el modelo de privacidad descrito.
8. Free y Pro respetan los límites aprobados y el backend verifica Billing.
9. La app ofrece reporte de IA, privacidad y borrado de cuenta dentro y fuera de la APK.
10. Las pruebas críticas, el build release y las comprobaciones de Google Play pasan sin bloqueadores.

## 21. Riesgos y mitigaciones

| Riesgo | Mitigación |
|---|---|
| Pérdida de campos por drift local/cloud | Contrato versionado, migración previa y fixtures Kotlin↔TypeScript |
| Conflictos entre dispositivos | Outbox durable, cursor monotónico, LWW determinista y tests de convergencia |
| Reintentos que duplican cambios | `mutationId` idempotente y transacciones server-side |
| Pérdida al editar histórico | Borrador aislado, guardado atómico y tombstones |
| Sesión expirada offline | Datos locales preservados; red pausada hasta reautenticar |
| Exposición de fotos | Bucket privado, URLs firmadas y almacenamiento temporal privado |
| Abuso/coste de IA | Derecho Pro server-side, rate limits y límites operativos remotos |
| Fraude de Billing | Verificación server-side y notificaciones de Google Play |
| Rechazo de Play | API 36, Data Safety, borrado de cuenta, reporte IA y pre-launch report |
| Diferencias de comportamiento con la PWA | Tests existentes como especificación y E2E de paridad |

## 22. Referencias oficiales

- Jetpack Compose: <https://developer.android.com/compose>
- Arquitectura offline-first: <https://developer.android.com/topic/architecture/data-layer/offline-first>
- Clerk Android: <https://clerk.com/docs/android/getting-started/quickstart>
- Target API de Google Play: <https://support.google.com/googleplay/android-developer/answer/11926878>
- Android App Bundles: <https://support.google.com/googleplay/android-developer/answer/9844279>
- Política de pagos: <https://support.google.com/googleplay/android-developer/answer/10281818>
- Borrado de cuenta y datos: <https://support.google.com/googleplay/android-developer/answer/10144311>
- Política de contenido generado por IA: <https://support.google.com/googleplay/android-developer/answer/13985936>
- Requisitos de testing para cuentas personales: <https://support.google.com/googleplay/android-developer/answer/14151465>
