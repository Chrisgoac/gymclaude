# Instrucciones para la revisión de Google Play (Contenido de la app → Acceso a la app)

Borrador pendiente de aprobación del dueño. **Ninguna credencial va en git**: el email y la contraseña de la
cuenta de revisión se escriben solo en Play Console (Acceso a la app → "Todas o algunas funciones están
restringidas" → instrucciones). Aquí solo hay marcadores como `<EMAIL_CUENTA_REVISION>`.

## 1. Preparar la cuenta de revisión (dueño, antes de enviar)

1. Crear una cuenta de GymLog **exclusiva para la revisión**, distinta de la cuenta personal del dueño y sin datos
   reales: "Crear cuenta" en la app, con un email del dueño reservado para esto
   (`<EMAIL_CUENTA_REVISION>`) y **contraseña** (`<CONTRASEÑA_CUENTA_REVISION>`). El revisor no puede recibir
   códigos por email, así que la cuenta debe poder entrar solo con contraseña.
2. En el panel de Clerk, comprobar que esa cuenta no exige segundo factor ni verificación de dispositivo nuevo.
   [confirmar por el dueño] (ajustes de la instancia de Clerk de producción).
3. Cargar algunos datos de ejemplo con esa cuenta: 1–2 rutinas, 3–4 entrenos, alguna medida corporal, para que
   Progreso, el Coach y el informe tengan contenido.
4. **Pro para el revisor.** El servidor solo concede Pro tras verificar una compra con Google Play (las filas de
   prueba `source='test'` no valen en producción). Opciones:
   - a) Antes de enviar, entrar en la app con la cuenta de revisión en un dispositivo cuya cuenta de Google sea
     **tester de licencias** del proyecto y comprar Pro (compra de prueba, sin cargo real). ⚠️ Las suscripciones de
     prueba de licencias se renuevan en minutos y se cancelan solas tras pocas renovaciones, así que el Pro puede
     haber caducado cuando llegue el revisor.
   - b) Dejar que el revisor compre Pro desde la app (la compra sigue las reglas de Google para revisores).
   [confirmar por el dueño] (opción elegida y, si es la a, cuándo repetir la compra).
5. Cuenta adicional para probar el borrado: el revisor **no debe borrar** la cuenta de revisión. Indicarle que
   cree su propia cuenta en la app ("Crear cuenta") para probar "Eliminar cuenta".

## 2. Texto para Play Console (pegar en "Instrucciones", en inglés)

> GymLog requires an account. Sign in on the first screen with:
> Email: <EMAIL_CUENTA_REVISION> — Password: <CONTRASEÑA_CUENTA_REVISION>
> (tap "Entrar", type the email and the password, then "Entrar"). The app interface is in Spanish.
>
> Free features (no subscription): tabs "Entrenar" (start a workout from a routine, log sets, rest timer),
> "Rutinas", "Ejercicios", "Progreso" (charts, records, "Cuerpo" for body measurements and progress photos) and
> "Historial".
>
> GymLog Pro (AI features, subscriptions gymlog_pro_monthly / gymlog_pro_annual):
> - Subscription screen: open "Ajustes" from the top bar of any main tab → "Suscripción" → "Ver GymLog Pro".
>   Plans and prices come from Google Play.
> - AI Coach: "Entrenar" tab → "Coach" card → "Pregunta a tu entrenador IA". Each answer has a "Reportar" button
>   (report offensive, harmful or medical content).
> - AI mesocycle: "Rutinas" tab → "Mesociclo con IA" → choose options → review → "Guardar mesociclo".
> - Intelligent report: "Progreso" tab → "Informe inteligente".
> Without Pro these entry points show a lock and a "Ver GymLog Pro" button.
>
> Account deletion: please do NOT delete this review account. To test deletion, create your own account
> ("Crear cuenta") and go to "Ajustes" → "Cuenta y privacidad" → "Eliminar cuenta" (you will be asked to confirm
> your password and type ELIMINAR). Web deletion: https://gym.chrisgoac.dev/delete-account.
> Privacy policy: https://gym.chrisgoac.dev/privacy (also in "Ajustes" → "Cuenta y privacidad" → "Privacidad").

## 3. Comprobar antes de enviar

- [ ] La cuenta de revisión entra con email + contraseña en un dispositivo limpio, sin pedir código.
- [ ] Las rutas de navegación del texto coinciden con la versión subida (nombres de pestañas y botones).
- [ ] Estado de Pro de la cuenta de revisión según la opción elegida en 1.4.
- [ ] `/privacy` y `/delete-account` responden 200 sin sesión.
- [ ] Ninguna credencial en git, issues ni capturas.
