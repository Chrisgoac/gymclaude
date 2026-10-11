# Recursos gráficos y URLs de la ficha (checklist)

Requisitos de Play Console para la ficha principal. Marcar cada casilla al subir el recurso.

## Gráficos

| Recurso | Requisito de Play | Estado / origen |
|---|---|---|
| Icono de la app (ficha) | PNG de 32 bits (con alfa), **512 × 512 px**, ≤1 MB; Play aplica la máscara redondeada, no añadir sombras ni esquinas | `public/icon-512.png` existe (512×512, RGB **sin alfa**): exportarlo de nuevo con canal alfa o confirmar que Play lo acepta. [confirmar por el dueño] |
| Icono del lanzador en el APK | Icono adaptativo (`mipmap-anydpi-v26` con primer plano/fondo) y `android:icon` en el manifiesto | ⚠️ **Falta**: el manifiesto fusionado no tiene `android:icon` y `res/` no tiene `mipmap*`; Android mostraría el icono genérico. Debe añadirse antes de publicar (fuera del alcance de esta tarea de documentación). |
| Gráfico de funciones (feature graphic) | JPEG o PNG de 24 bits (sin alfa), **1024 × 500 px**, ≤15 MB; sin texto esencial en los bordes | Por crear. |
| Capturas de teléfono | **2 a 8**; JPEG o PNG de 24 bits; relación **16:9 o 9:16**; cada lado entre **320 y 3840 px** (el lado largo ≤2× el corto) | Por crear. Recomendadas 4+ en 1080×1920 o 1080×2400 recortadas a 9:16 (para ser apta para recomendaciones se piden al menos 4 de ≥1080 px). |
| Capturas de tablet 7" | Mismas reglas; solo si se declara compatibilidad con tablets en la ficha | Opcional. La app no restringe tamaños de pantalla (sin `<supports-screens>` ni `requiresSmallestWidth`), así que se instala en tablets aunque no se suban capturas. [confirmar por el dueño] (subir o no) |
| Capturas de tablet 10" | Mismas reglas | Opcional, igual que la anterior. |
| Vídeo promocional | URL de YouTube, opcional | No previsto. |

### Contenido de las capturas (propuesta)

Usar solo la cuenta de revisión o datos inventados; **nunca** datos reales, emails o fotos de personas reales.

1. Entrenar: entreno en curso con series y temporizador de descanso.
2. Rutinas: lista de rutinas y la siguiente propuesta.
3. Progreso: gráfica de un ejercicio y récords.
4. Cuerpo: medidas (sin fotos de cuerpo real; usar un marcador o ninguna foto).
5. Historial.
6. Coach (Pro): una pregunta de técnica y la respuesta con el aviso "no es consejo médico" visible.
7. GymLog Pro: pantalla de planes (precios de ejemplo de Play; no inventar precios en el texto de la captura).

Reglas: sin afirmaciones de salud ni promesas de resultados en los textos superpuestos; sin insignias de "n.º 1",
"mejor app" o similares; la barra de estado limpia (sin notificaciones personales).

## URLs (deben coincidir con `BuildConfig` de Android)

| URL | Valor | Origen en el código | Comprobación |
|---|---|---|---|
| Política de privacidad | `https://gym.chrisgoac.dev/privacy` | `PRIVACY_POLICY_URL` = `<gymlog.webBaseUrl>/privacy` (`android/app/build.gradle.kts`, por defecto `https://gym.chrisgoac.dev`); página `app/privacy/page.tsx` | [ ] responde 200 sin sesión tras el despliegue |
| Borrado de cuenta | `https://gym.chrisgoac.dev/delete-account` | `ACCOUNT_DELETION_URL` = `<gymlog.webBaseUrl>/delete-account`; página `app/delete-account/page.tsx` | [ ] responde 200 sin sesión |
| Suscripciones de Google Play | `https://play.google.com/store/account/subscriptions?package=com.chrisgoac.gymlog` | `playManageUrl()` (`lib/account/deletion.ts`) y `BillingRepository.managementUri()` | — |
| Sitio web | `https://gym.chrisgoac.dev` | `gymlog.apiBaseUrl` por defecto | — |

⚠️ Si el `local.properties` del build de release define otro `gymlog.webBaseUrl`, las URLs de la ficha deben
cambiar a ese dominio. [confirmar por el dueño] (dominio definitivo de producción).

## Otros campos obligatorios de Play Console

- [ ] Clasificación de contenido (cuestionario IARC), incluida la pregunta de contenido generado por IA
      (`ai-content.md`).
- [ ] Público objetivo y edad mínima. [confirmar por el dueño]
- [ ] Seguridad de los datos (`data-safety.md`).
- [ ] Acceso a la app (`reviewer-instructions.md`).
- [ ] Anuncios: "No".
- [ ] Declaración de apps de salud (Health apps declaration) si Play la pide por la categoría elegida: GymLog es
      seguimiento de entrenamiento, no dispositivo médico ni servicio sanitario.
- [ ] Correo de contacto del desarrollador (el que citan `/privacy` y `/delete-account`).
