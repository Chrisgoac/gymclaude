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

## Convergencia offline y gate de fase (Task 9)

Base: `3835303` (feat/android-workouts) + los tests de esta tarea; el commit de la tarea es
`test(android): verify offline workout convergence`. Fecha: 2026-10-11. JDK: JBR de Android Studio.

### Arnés JVM de convergencia (Robolectric, API 36)

`core/data/src/test/.../sync/FakeSyncServer.kt` sustituye a `/api/v1/sync/{push,pull}` de un usuario,
siguiendo `contracts/v1/README.md`: petición transaccional, mutación atómica y deduplicada por
`mutationId` (un id conocido devuelve sus conflictos guardados y no escribe), LWW por tupla con el
`ConflictResolver` de Kotlin (gemelo de `server-merge.ts`), `serverVersion` = secuencia global del
journal, `FUTURE_TIMESTAMP` a +5 min, pull paginado que emite solo la última versión de cada registro
(las entradas antiguas avanzan el cursor), cursor opaco base64url y `INVALID_CURSOR`. Las peticiones y
respuestas pasan por los codecs del contrato (`ContractJson` estricto / `ResponseJson`). Por debajo de
`SyncApi` todo es código de producción (`SyncRepositoryImpl`, `BootstrapRepository`, `PullPageApplier`,
`RoomLocalMutationRunner`, repositorios offline).

`WorkoutConvergenceTest` monta dos instalaciones independientes (fichero Room, `deviceId`, outbox y
pila de sync propios; misma cuenta):

1. **A sin red desde la primera escritura:** gimnasios, rutina, empezar la rutina, registrar 5 series,
   reordenar (la más pesada arriba), editar reps; **matar el proceso** (cerrar la BD y reconstruir los
   repositorios sobre el mismo fichero) y comprobar que el entreno activo, sus ids y su orden siguen;
   terminar; **editar el histórico** (fecha −2 días, gimnasio, peso, quitar una serie, mover otra,
   quitar un ejercicio, añadir otro con su serie). Un intento de sync sin red deja todo en cola.
2. **Reconexión con entrega duplicada:** el primer push llega y se aplica pero se pierde la respuesta
   (timeout); el worker vuelve a correr y reenvía **los mismos bytes**. Resultado: cada mutación aplicada
   **una sola vez** en el servidor (`applications == 1` para todas), al menos una respuesta servida desde
   el recibo, outbox de A vacía. Reenviar la petición idéntica otra vez no añade nada al journal.
3. **B (instalación nueva) hace bootstrap** en páginas de 4 cambios: el estado completo de B (las 14
   tablas, registros de cable incluidos `updatedAt`, `orden`, valores y **tombstones**) es igual al de A y
   al del servidor; el agregado del historial (`WorkoutAggregate`) de B es igual al editado en A; la serie
   y el ejercicio quitados llegan como tombstones; `orden` compacto e idéntico.
4. **Edición concurrente de la misma serie** (A reps 9, B reps 11, ambos sin red) en los dos órdenes de
   sincronización y con `updatedAt` distinto y empatado (desempate por `deviceId`): los dos dispositivos y
   el servidor terminan con el ganador LWW (B) y sin outbox pendiente.

Comprobación de que el arnés detecta fallos (mutantes temporales, revertidos): desactivar la
deduplicación del servidor hace fallar el escenario 2; aplicar siempre (sin LWW) hace fallar el 4.

```
./gradlew :core:data:testDebugUnitTest --tests '*WorkoutConvergenceTest*'
WorkoutConvergenceTest: tests=2 failures=0 errors=0 (offlineRoutineWorkoutAndHistoryEditConvergeOnASecondDevice,
  concurrentOfflineEditsOfTheSameSetConvergeToTheLwwWinnerInEitherSyncOrder)
```

El temporizador de descanso en segundo plano no se repite aquí: lo cubre Task 6 (arriba).

### E2E de interfaz en emulador (sin red, sin Clerk)

`app/src/androidTest/.../WorkoutOfflineE2eTest.kt` pinta `GymLogRoot` en `RootState.Ready` con una BD
Room en memoria y los repositorios offline reales (sin `SyncApi`, sin sesión). No hace falta ningún
punto de inyección en producción: `GymLogRoot` ya recibe todas sus dependencias. Flujos:

- Rutina: Entrenar → «Empezar Empuje» → añadir/teclear/marcar 2 series → Terminar → Resumen → Historial →
  detalle (`1. 60 kg × 8`, `2. 62.5 kg × 6`) → Editar entreno → reps 10 → Guardar cambios → detalle
  `1. 60 kg × 10`; Room refleja los valores y la edición es **una** fila más de outbox.
- Libre: «Entrenamiento libre» → Añadir ejercicio (buscador) → serie → Terminar → aparece en Historial.

```
~/Android/Sdk/emulator/emulator -avd gymlog_api36 -no-window -no-audio -no-snapshot -memory 2048 &
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.chrisgoac.gymlog.WorkoutOfflineE2eTest
  Finished 2 tests on gymlog_api36(AVD) - 16  (0 failed)
./gradlew :app:connectedDebugAndroidTest
  Finished 8 tests on gymlog_api36(AVD) - 16  (0 failed)   # sdk 36; NavigationSmokeTest + este E2E
adb emu kill
```

### Compose sin WebView

- `grep -rniE "webview|webkit"` en `android/` (kts, toml, kt, xml; sin `build/`): 0 resultados.
- `./gradlew :app:dependencies --configuration debugRuntimeClasspath` y `releaseRuntimeClasspath`:
  0 líneas con `webkit`/`webview`.
- Ninguna pantalla de `app`/`feature/*`/`core:designsystem` importa `android.view`/`android.widget`, usa
  `setContentView` ni `AndroidView(`: todas las pantallas son Compose.

### Suite completa

```
./gradlew testDebugUnitTest test lintDebug assembleDebug verifyModuleGraph   -> BUILD SUCCESSFUL
  resultados JUnit: 552 tests, 0 fallos, 0 errores, 0 omitidos
  lint (app): 0 errors, 24 warnings (preexistentes; p. ej. MissingPermission de setExactAndAllowWhileIdle,
  protegido por canScheduleExactAlarms() según la decisión de Task 6)
```

### Comprobaciones pendientes en dispositivo (no automatizadas esta noche)

- [ ] `testDebug lintDebug connectedDebugAndroidTest assembleDebug` en **API 26** (emulador o dispositivo):
      no hay imagen API 26 disponible; solo se ha ejecutado en API 36.
- [ ] Temporizador en Pixel y Samsung (lista de arriba, Task 6).
- [ ] Dos dispositivos reales contra el servidor real (`https://gym.chrisgoac.dev`) cuando Clerk esté
      configurado (`clerk.publishableKey`): entreno offline en A + matar app + editar histórico + reconectar;
      B inicia sesión y compara historial; edición concurrente de la misma serie.
- [ ] Doble entrega real de WorkManager en dispositivo (forzar reintento con modo avión a mitad de push).
