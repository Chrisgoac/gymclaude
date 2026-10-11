# Android fase 4: insights, ajustes y copia de seguridad, verificación

Base: `adda6ca` (feat/android-insights) + los tests de esta tarea; el commit de la tarea es
`test(android): verify insights and settings parity`. Fecha: 2026-10-11. JDK: JBR de Android Studio.
Emulador: AVD `gymlog_api36` (Pixel 6, API 36, google_apis x86_64), headless, apagado con `adb emu kill`.

**Estado del gate: evidencia automatizada completa; comprobaciones en dispositivo pendientes.**

## 1. Fixtures de dominio compartidos: diff cero TypeScript / Kotlin

Los ocho `contracts/domain/*.json` se ejecutan en los dos runtimes. Los casos salen de los propios JSON;
los tests de ambos lados los leen del repo (nunca copias).

| Fixture | Test TypeScript (vitest) | Casos TS | Test Kotlin | Casos Kotlin |
|---|---|---|---|---|
| `num-cases.json` | `lib/num.test.ts` | 102 | `DomainParityTest` | 10 (agrupa fixtures num, fecha, period, body-stats) |
| `fecha-cases.json` | `lib/fecha.test.ts` | 55 | `DomainParityTest` | (incluido arriba) |
| `period-cases.json` | `lib/period.test.ts` | 43 | `DomainParityTest` | (incluido arriba) |
| `body-stats-cases.json` | `lib/body-stats.test.ts` | 12 | `DomainParityTest` | (incluido arriba) |
| `insight-cases.json` | `lib/insights.test.ts` | 10 | `InsightsParityTest` + `OfflineStatsRepositoryTest` (Room) | 6 + 3 |
| `achievement-cases.json` | `lib/logros-parity.test.ts` | 67 | `AchievementParityTest` + `OfflineAchievementRepositoryTest` (Room) | 5 + 2 |
| `progression.json` | `lib/progresion.test.ts` | 62 | `ProgressionTest` | 5 |
| `routine-rotation.json` | `lib/routine-rotation.test.ts` | 14 | `RoutineRotationTest` | 1 |
| `contracts/v1` conflictos | (servidor) | - | `ConflictResolverTest` | 2 |

Comandos y resultados:

```
npx vitest run lib/body-stats.test.ts lib/fecha.test.ts lib/num.test.ts lib/logros-parity.test.ts \
  lib/period.test.ts lib/insights.test.ts lib/progresion.test.ts lib/routine-rotation.test.ts
  -> Test Files 8 passed (8); Tests 365 passed (365)

cd android && ./gradlew testDebugUnitTest test --no-build-cache --rerun-tasks
  -> BUILD SUCCESSFUL; 780 tests, 0 failures, 0 errors, 0 skipped (todos los módulos)
```

Cada test Kotlin compara con el fixture los valores aprobados (incluidos los resultados que difieren
a propósito de la PWA, registrados en el propio fixture: p. ej. el calentamiento cuenta en los PRs, la
reconciliación de logros solo revoca tras una edición local). Un fallo en cualquiera de los dos lados rompe
el build; no hay diff entre ambos porque los dos leen el mismo JSON.

## 2. Una edición histórica mueve todo de forma consistente

### 2.1 JVM: `HistoricalEditConsistencyTest` (core:data, Robolectric/Room)

Historial de 10 entrenos de Press de banca; una edición (110 kg y movido a hoy) y un borrado local:

- Tras la edición, y desde un único evento: récord de peso 110, 1RM 128,3, volumen del periodo 5050,
  balance muscular 5050 con 0 días sin entrenar, racha de días 1, resumen semanal 550 con récord de tipo peso,
  ejercicios estancados vacío.
- Los logros se conservan tras la edición (no revoca nada que siga ganado) y el evento `COACH_SNAPSHOT` se
  publica una vez. No existe aún un constructor de snapshot del Coach en Android: la entrada del Coach es el
  evento de invalidación, que es lo que se comprueba.
- Tras borrar el entreno (edición local): PR vuelve a 100, 1RM 116,7, volumen 4500 con 9 sesiones, racha 0,
  `sesiones-10` queda tombstoned (solo una edición/borrado local puede revocar) y `racha-4` se conserva;
  segundo evento `COACH_SNAPSHOT`.

### 2.2 En pantalla: `FreeParityE2eTest.loggedWorkoutAndEditOfAnOldOneMoveProgressRecordsAndAchievementsConsistently`

Con 9 sesiones antiguas sembradas (10 kg x 5) más una registrada por la UI (40 kg x 5):
Progreso muestra récord de la semana 1, 1 sesión, volumen 200, racha 1; en «Todo» 10 sesiones y 650;
Logros muestra «Calentando» desbloqueado y el PR 40 kg. Editando desde Historial el entreno más antiguo
(10 -> 60 kg) la pantalla pasa a volumen 900, récords de la semana 0, Press de banca como estancado y PR
60 kg; la edición es una mutación lógica más en el outbox.

## 3. Cuerpo, gimnasios, tema, orden de rutinas y copia de seguridad, sin red

`FreeParityE2eTest` (androidTest, API 36, sin red ni sesión Clerk: `GymLogRoot` en `RootState.Ready` sobre
Room en memoria con los repositorios y ViewModels reales; no hay seam de producción nuevo).

- `bodyGymsThemeRoutineOrderAndGoalAreSavedOfflineAndSurviveReopening`: añade una medición corporal (80,5),
  crea un gimnasio, lo marca como predeterminado y lo archiva; cambia el tema a Oscuro (DataStore, nunca el
  outbox), fija el objetivo semanal 4 (fila sincronizada `objetivoSemanal`) y reordena rutinas (orden en BD);
  comprueba que lo sincronizable quedó en el outbox.
- `backupExportDeleteImportRestoresEverythingOffline`: el selector SAF no se puede automatizar de forma fiable,
  así que se usa `BackupViewModel` con un `BackupDocuments` en memoria (el seam bajo el selector). Exporta
  (archivo con formato PWA, sin tokens ni `serverVersion`), vacía todas las tablas, importa en modo Combinar
  (restaura exactamente las mismas filas vivas) y luego en modo Reemplazar con confirmación (elimina el entreno
  extra que no está en el archivo).
- Unidad de peso: la opción lb sigue oculta a propósito (ruling de paridad con la PWA), por eso no se prueba.

## 4. Suites completas

```
cd android && ./gradlew testDebugUnitTest test lintDebug assembleDebug verifyModuleGraph
  -> BUILD SUCCESSFUL (verifyUniqueResourceNames: 814 recursos, sin duplicados)
./gradlew :app:connectedDebugAndroidTest   (emulador gymlog_api36)
  -> Finished 11 tests on gymlog_api36(AVD): 11/11
     FreeParityE2eTest 3, WorkoutOfflineE2eTest 2, NavigationSmokeTest 6
```

## 5. Accesibilidad (evidencia automatizada)

Pruebas de semántica Compose que corren en cada `testDebugUnitTest` (Robolectric):

- `ProgressScreensTest` (19): resúmenes semanales como una parada cada uno a doble tamaño de fuente, gráfico
  de volumen con resumen textual, un nodo semántico por barra y tabla alternativa con los mismos datos, balance
  muscular text-first con grupo descuidado indicado en texto, estancados con sugerencia.
- `BodyScreensTest` (10): gráfico con resumen y tabla alternativa, deltas hablados, edición con errores.
- `AchievementsScreenTest` (4), `SettingsScreensTest` (2), `NavigationShellTest` (7) y `ActiveWorkoutScreenTest`
  (fuente 2x).
- Dispositivo (API 36): `NavigationSmokeTest.bodyScalesAndTabsLegibleAt200PercentFont`, `touchTargets`,
  `selectedSemantics`.

Ninguna métrica depende solo del color ni de un gesto: cada gráfico expone resumen hablado y «Ver como tabla».

## 6. Comprobaciones manuales pendientes (dispositivo físico, aplazadas por el controlador)

- [ ] TalkBack en dispositivo real: recorrer Progreso (resumen, gráfico, tabla alternativa), Cuerpo, Logros
      y Ajustes; comprobar el orden de lectura y que «seleccionado» no se anuncia dos veces en los chips.
- [ ] API 26 (emulador o dispositivo): `connectedDebugAndroidTest` completo, tamaño de fuente 200 % y
      Accessibility Scanner.
- [ ] Selector SAF real: exportar a un archivo (Drive/Descargas) y reimportar; cancelar el selector.
- [ ] Importar en la PWA un archivo exportado desde Android y viceversa (formato compartido).
- [ ] Cambio de tema en caliente (primer frame sin parpadeo) y modo claro/oscuro del sistema.
- [ ] Gestos de borde y teclado (insets) en los editores de medición y de gimnasio.

## 7. Candidatos de la ola final (ya registrados en el ledger)

Merge de copia con `updatedAt` futuro (> +5 min) sería rechazado por el servidor (`FUTURE_TIMESTAMP`);
filtros del dispositivo guardados pero aún no leídos por Progreso/Historial; chips con nodos < 48 dp a partir
de ~8 puntos de gráfico; parpadeo de tema al primer frame.
