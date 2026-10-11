# Contenido generado por IA (política de Google Play y declaración en Play Console)

Borrador pendiente de aprobación del dueño. Derivado del código de `feat/android-release`.

## Qué genera la IA en GymLog

| Función | Ruta del servidor | Qué recibe el modelo | Qué devuelve | Dónde se guarda |
|---|---|---|---|---|
| Coach (chat) | `POST /api/v1/ai/coach` (streaming) | Mensaje del usuario (≤2000 caracteres), últimos turnos de su conversación (de `coach_messages`) y un resumen de entreno acotado (`CoachSnapshotSchema`: semana, estancados, récords, grupos musculares, peso y ≤6 medidas). | Texto | `ai_responses` (kind `coach`) y `coach_messages` sincronizado |
| Mesociclo | `POST /api/v1/ai/mesocycles` | Objetivo, días/semana, semanas, minutos por sesión, el mismo resumen y ≤400 ejercicios del catálogo (nombre, grupo, equipo). | Objeto JSON validado contra esquema estricto; el usuario lo revisa antes de guardarlo como rutinas. | `ai_responses` (kind `mesocycle`) |
| Informe inteligente | `POST /api/v1/ai/reports` | El mismo resumen. | Objeto JSON validado. | `ai_reports` (caché por huella del resumen) |

- Modelo: DeepSeek `deepseek-chat` vía AI SDK (`lib/coach-model.ts`), sin reintentos ocultos y con tiempo máximo
  (Coach 45 s).
- Nunca se envían email, id de usuario, fotos, URLs ni claves de objetos (lista blanca estricta en
  `lib/api/v1/ai/contracts.ts`; los registros del servidor solo llevan ids y códigos, nunca el texto).
- Acceso: solo con GymLog Pro activo en Android (`requirePro`, falla cerrado) y con cupo por usuario (20/min y
  200/día, `enforceAiBudget`).
- ⚠️ La versión web (PWA) tiene un Coach antiguo (`POST /api/coach`) que llama a DeepSeek **sin** exigir Pro y
  con el historial que manda el navegador. No afecta a la app Android, pero sí a la política de privacidad (que ya
  no dice "solo Pro") y a la descripción "Pro = IA". [confirmar por el dueño] (mantenerlo, exigir Pro o retirarlo).

## Salvaguardas

- **Sin consejo médico:** el prompt del Coach v1 y del mesociclo incluyen `MEDICAL_DISCLAIMER_INSTRUCTION`
  (`lib/coach-prompt.ts`): no diagnosticar, no recomendar tratamientos/medicación/suplementos, derivar a un
  profesional ante dolor o síntomas.
- **Aviso visible** en las tres pantallas (`coach_disclaimer`, `routines_meso_disclaimer`,
  `progress_ai_report_disclaimer`): "Esto es orientación general de entrenamiento, no consejo médico…".
- **Salida acotada:** mesociclo e informe se validan contra esquema estricto; un mesociclo solo puede usar
  ejercicios del catálogo enviado; el usuario lo confirma antes de guardarlo.

## Denuncia de contenido ofensivo dentro de la app (requisito de Play)

- **Coach:** cada respuesta tiene "Reportar" → categorías Incorrecta, Peligrosa, Da consejo médico, Ofensiva,
  Otro motivo (Android no envía texto libre; la API admite un contexto opcional de ≤500 caracteres). `POST /api/v1/ai/responses/:id/report` → tabla
  `reported_ai_responses`.
- ⚠️ **Mesociclos e informes inteligentes no tienen botón de reportar** en Android (la tabla y la ruta aceptan
  respuestas `mesocycle`, pero no hay UI; el informe no es una `ai_response`). Hasta añadirlo, la política y la
  ficha indican el correo de contacto para estos casos. Recomendación: añadir "Reportar" a ambas pantallas antes
  de enviar a revisión, porque la política de Play pide la denuncia **dentro de la app** para todo el contenido
  generado. [confirmar por el dueño] (añadirlo o aceptar el riesgo).
- **Revisión de los reportes:** no hay panel; se consultan en la base de datos (`reported_ai_responses`).
  [confirmar por el dueño] (quién los revisa y con qué frecuencia).

## Textos que deben aparecer en la ficha y en la app

- "El Coach usa inteligencia artificial: puede equivocarse y no sustituye a un profesional sanitario ni es
  consejo médico."
- No prometer resultados físicos, pérdida de peso, mejora de salud ni prevención de lesiones.

## Declaración en Play Console

- Cuestionario de clasificación de contenido: la app **genera contenido con IA** y el usuario **interactúa** con
  ella (chat). Responder "Sí" a contenido generado por IA cuando lo pregunte.
- Política de apps de IA generativa: cumplir "denuncia dentro de la app" (ver ⚠️ arriba) y que el modelo no
  genere contenido restringido (el prompt limita el tema a entrenamiento).
