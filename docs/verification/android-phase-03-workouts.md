# Android fase 3: entrenamientos nativos, verificación

## Temporizador de descanso (Task 6)

Automatizado (JVM / Robolectric API 36): estado en epoch millis, pausa/reanudar/extender,
muerte de proceso (reconstrucción desde DataStore), cambio de zona horaria, finalización
exactamente una vez, programación de alarma exacta / no exacta, nada programado en pausa,
aislamiento por usuario del DataStore, canal y notificación local.

### Decisiones

- Alarma: `setExactAndAllowWhileIdle` solo si `canScheduleExactAlarms()` (API 31+) es true; si no,
  `setAndAllowWhileIdle`. La app no declara ni pide `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`, así que en la
  práctica casi siempre usa la inexacta (puede retrasarse unos minutos con Doze).
- `POST_NOTIFICATIONS` (API 33+): se pide en contexto, una sola vez, al arrancar el primer descanso.
  Si se rechaza, el temporizador sigue funcionando en la app; solo se pierde el aviso en segundo plano.
- Canal "Descanso": IMPORTANCE_DEFAULT (un sonido corto al terminar, sin heads-up), sin badge, visibilidad
  pública (no contiene datos personales). Sin notificaciones remotas ni promocionales.
- Sin objetivo de descanso en la rutina no arranca temporizador (paridad PWA: allí es un cronómetro libre).
- Las alarmas no sobreviven a un reinicio del dispositivo: el temporizador reaparece al abrir el entrenamiento
  (se reprograma en `restore`), pero no avisa hasta entonces.

### Comprobaciones manuales pendientes (dispositivo físico, aplazadas por el controlador)

- [ ] Pixel (API 34+): terminar una serie con descanso, bloquear la pantalla, comprobar que la notificación
      llega en el momento (±1 min) y que al tocarla se abre el entrenamiento activo.
- [ ] Samsung One UI: igual; revisar "Batería > Límites de uso en segundo plano" (apps en suspensión /
      suspensión profunda). Si la app está en "dormida", las alarmas pueden retrasarse o perderse.
- [ ] Otros fabricantes (Xiaomi/MIUI, Huawei, OnePlus, Oppo): el ahorro agresivo de batería y el "autoarranque"
      pueden matar la app y cancelar alarmas; documentar qué ajuste hay que desactivar.
- [ ] Modo No molestar / modo concentración: la notificación no suena (esperado).
- [ ] Android 13+: rechazar el permiso de notificaciones: el temporizador sigue en pantalla, sin aviso en segundo plano.
- [ ] Android 12/12L y 13 con "Alarmas y recordatorios" desactivado/activado: confirmar retraso observado.
- [ ] Háptico al terminar con la app en primer plano y anuncio de TalkBack ("Descanso terminado").
- [ ] Cambio de zona horaria y de hora manual con el temporizador activo (la cuenta atrás usa reloj de pared).
- [ ] API 26 (emulador/dispositivo): canal y alarma.
