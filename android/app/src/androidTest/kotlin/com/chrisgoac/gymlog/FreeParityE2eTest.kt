package com.chrisgoac.gymlog

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chrisgoac.gymlog.data.achievement.OfflineAchievementRepository
import com.chrisgoac.gymlog.data.backup.RoomBackupRepository
import com.chrisgoac.gymlog.data.body.OfflineBodyRepository
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.data.routine.OfflineRoutineRepository
import com.chrisgoac.gymlog.data.settings.OfflineGymRepository
import com.chrisgoac.gymlog.data.settings.OfflineSettingsRepository
import com.chrisgoac.gymlog.data.stats.OfflineStatsRepository
import com.chrisgoac.gymlog.data.workout.OfflineHistoryRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutEditRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutLogRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.devicesettings.DataStoreDeviceSettings
import com.chrisgoac.gymlog.devicesettings.UserScopedPreferenceStores
import com.chrisgoac.gymlog.domain.SyncStatus
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.achievement.AchievementReconcileScheduler
import com.chrisgoac.gymlog.domain.achievement.ReconcileAchievementsUseCase
import com.chrisgoac.gymlog.domain.backup.BackupImportEffects
import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import com.chrisgoac.gymlog.domain.history.DefaultSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.routine.RoutineExerciseInput
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.math.WeightUnit
import com.chrisgoac.gymlog.domain.settings.ThemeMode
import com.chrisgoac.gymlog.settings.BackupPhase
import com.chrisgoac.gymlog.settings.BackupViewModel
import com.chrisgoac.gymlog.settings.BackupDocuments
import com.chrisgoac.gymlog.domain.backup.ImportMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import androidx.compose.ui.test.hasSetTextAction
import com.chrisgoac.gymlog.domain.BackgroundSyncScheduler
import com.chrisgoac.gymlog.domain.LocalAccountCleaner
import com.chrisgoac.gymlog.domain.SignOutUseCase
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.stats.InvalidatingHistoryRepository
import com.chrisgoac.gymlog.domain.stats.InvalidatingSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.stats.StatsInvalidation
import com.chrisgoac.gymlog.domain.timer.RestTimerAlarm
import com.chrisgoac.gymlog.domain.timer.RestTimerController
import com.chrisgoac.gymlog.domain.timer.RestTimerState
import com.chrisgoac.gymlog.domain.timer.RestTimerStore
import com.chrisgoac.gymlog.domain.workout.FinishWorkoutUseCase
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.navigation.AchievementsServices
import com.chrisgoac.gymlog.navigation.BackupServices
import com.chrisgoac.gymlog.navigation.BodyServices
import com.chrisgoac.gymlog.navigation.HistoryEditServices
import com.chrisgoac.gymlog.navigation.ProgressServices
import com.chrisgoac.gymlog.navigation.SettingsServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Free, non-media parity end to end on a device with no network and no Clerk session: [GymLogRoot] in
 * [RootState.Ready] over an in-memory Room database and the real repositories, ViewModels and screens
 * (the same wiring as AppContainer). The Storage Access Framework picker cannot be automated, so backup is
 * driven through [BackupViewModel] with a fake [BackupDocuments] (the seam below the picker).
 */
@RunWith(AndroidJUnit4::class)
class FreeParityE2eTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var db: GymLogDatabase
    private lateinit var runner: RoomLocalMutationRunner
    private lateinit var manager: UserDatabaseManager
    private lateinit var scope: CoroutineScope
    private lateinit var routines: OfflineRoutineRepository
    private lateinit var deviceSettings: DataStoreDeviceSettings
    private lateinit var backupRepository: RoomBackupRepository
    private val zone: ZoneId = ZoneId.systemDefault()

    private class Manager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase(USER, db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private class MemoryTimerStore : RestTimerStore {
        private var state: RestTimerState? = null
        override suspend fun read() = state
        override suspend fun write(state: RestTimerState?) { this.state = state }
    }

    private object NoAlarm : RestTimerAlarm {
        override fun schedule(runId: String, sessionId: String, endsAtEpochMillis: Long) = Unit
        override fun cancel() = Unit
    }

    /** In-memory SAF stand-in: a uri is a key; writes are committed on close. */
    private class FakeDocuments : BackupDocuments {
        val files = mutableMapOf<String, ByteArray>()
        override fun openForWrite(uri: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() { super.close(); files[uri] = toByteArray() }
        }
        override fun openForRead(uri: String): InputStream = ByteArrayInputStream(files.getValue(uri))
        override fun delete(uri: String) { files.remove(uri) }
    }

    private inline fun <reified T : Any> stub(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException("${method.name} is not used by this test") } as T

    private val documents = FakeDocuments()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).build()
        runner = RoomLocalMutationRunner(db, DeviceId("e2e-device"))
        manager = Manager(db)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runnerFor = { _: GymLogDatabase -> runner }
        val invalidation = StatsInvalidation()
        val exercises = OfflineExerciseRepository(manager, runnerFor)
        routines = OfflineRoutineRepository(manager, runnerFor, exercises)
        val workouts = OfflineWorkoutRepository(manager, runnerFor)
        val restTimer = RestTimerController(MemoryTimerStore(), NoAlarm)
        val stats = OfflineStatsRepository(manager, System::currentTimeMillis) { zone }
        val achievements = OfflineAchievementRepository(manager, runnerFor)
        val reconcile = ReconcileAchievementsUseCase(achievements, now = System::currentTimeMillis, zone = { zone })
        scope.launch {
            AchievementReconcileScheduler(invalidation, flowOf(Unit), flowOf(true), currentAccount = { "e2e" }, reconcile = { reconcile(it) }, debounceMillis = 100).run()
        }
        backupRepository = RoomBackupRepository(manager, runnerFor, System::currentTimeMillis, BackupImportEffects(invalidation, requestSync = {}))
        val dataDir = File(context.cacheDir, "e2e-" + UUID.randomUUID()).also { it.mkdirs() }
        deviceSettings = DataStoreDeviceSettings(
            PreferenceDataStoreFactory.create(scope = scope) { File(dataDir, "device.preferences_pb") },
            UserScopedPreferenceStores(dataDir),
            MutableStateFlow("e2ehash"),
        )
        val syncUseCases = object : SyncUseCases {
            override fun observeStatus() = flowOf(SyncStatus(false, 0, 0, true, null))
            override fun observeDiagnostics() = flowOf(emptyList<OutboxDiagnostic>())
            override suspend fun requestSync() = Unit
        }
        val signOut = SignOutUseCase({ USER }, stub<BackgroundSyncScheduler>(), stub<LocalAccountCleaner>(), stub<AuthGateway>())
        val gymRepository = OfflineGymRepository(manager, runnerFor)
        val settingsRepository = OfflineSettingsRepository(manager, runnerFor)
        val bodyRepository = OfflineBodyRepository(manager, runnerFor, System::currentTimeMillis)
        runBlocking {
            db.catalogDao().upsertAll(
                listOf(
                    CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null),
                    CatalogExerciseEntity("seed-remo", "Remo con barra", "espalda", "barra", "compuesto", null, null, null),
                ),
            )
            runner.run(LocalMutation { put(GymRecord(RecordId("g1"), nextTimestamp(SyncTable.GYMS, RecordId("g1")), null, "Fitness", 0, false)) })
            routines.create(RoutineInput("Empuje", "", listOf(RoutineExerciseInput("k-press", "seed-press", "Press de banca"))))
            seedHistory()
        }
        rule.setContent {
            GymLogTheme {
                GymLogRoot(
                    RootState.Ready(USER),
                    authGateway = { error("no auth screen in a Ready session") },
                    settings = SettingsServices(
                        syncUseCases, signOut, gyms = gymRepository, training = settingsRepository, device = deviceSettings,
                        backup = BackupServices(backupRepository, documents, System::currentTimeMillis, { zone }),
                    ),
                    exercises = exercises,
                    routines = routines,
                    workouts = workouts,
                    workoutLog = OfflineWorkoutLogRepository(manager, runnerFor),
                    restTimer = restTimer,
                    finishWorkout = FinishWorkoutUseCase(workouts, workouts, restTimer, onFinished = { invalidation.publish(setOf(InvalidationScope.ACHIEVEMENTS)) }),
                    history = InvalidatingHistoryRepository(OfflineHistoryRepository(manager, runnerFor, exercises, routines), invalidation),
                    historyEditing = HistoryEditServices(
                        InvalidatingSaveWorkoutDraftUseCase(
                            DefaultSaveWorkoutDraftUseCase(OfflineWorkoutEditRepository(manager, runnerFor), System::currentTimeMillis), invalidation,
                        ),
                        System::currentTimeMillis,
                    ),
                    progress = ProgressServices(stats, object : EntitlementReader { override fun observeIsPro() = flowOf(false) }, flowOf(Unit), System::currentTimeMillis) { zone },
                    body = BodyServices(bodyRepository, System::currentTimeMillis, { zone }, MutableStateFlow(WeightUnit.KG)),
                    achievements = AchievementsServices(stats, achievements) { zone },
                )
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** Nine older sessions (3 days apart, 10 kg x 5 of Press de banca), oldest first: s0 is the oldest. */
    private suspend fun seedHistory() {
        val now = Instant.now()
        for (i in 0 until 9) {
            val at = now.minus(Duration.ofDays(60L - i * 3)).toEpochMilli()
            db.workoutSessionDao().upsert(WorkoutSessionEntity("s$i", 1, null, null, null, null, at, 3600, null, null))
            db.loggedExerciseDao().upsert(LoggedExerciseEntity("le$i", 1, null, "s$i", "seed-press", 0, null))
            db.loggedSetDao().upsert(LoggedSetEntity("set$i", 1, null, "le$i", 0, 10.0, 5, false, null))
        }
    }

    private val verticalList = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun awaitText(text: String) {
        try {
            rule.waitUntil(TIMEOUT) { rule.onAllNodesWithText(text, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("Never saw \"$text\". Screen:\n" + dump(), e)
        }
    }

    private fun awaitTextContaining(text: String) {
        try {
            rule.waitUntil(TIMEOUT) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: Throwable) {
            throw AssertionError("Never saw text containing \"$text\". Screen:\n" + dump(), e)
        }
    }

    private fun awaitDescription(description: String) =
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }

    /** Every visible text / content description of the current screen (debug + assertions). */
    private fun texts(): List<String> = rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes().flatMap { node ->
        listOfNotNull(
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" | ") { it.text },
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" | ")?.let { "[cd] $it" },
        )
    }

    private fun dump(): String = texts().joinToString("\n")

    private fun sql(query: String): List<List<String?>> = db.query(query, null).use { c ->
        buildList { while (c.moveToNext()) add((0 until c.columnCount).map { c.getString(it) }) }
    }

    private fun finishedSessions(): Int = sql("SELECT COUNT(*) FROM workout_sessions WHERE deletedAt IS NULL AND duracionSegundos IS NOT NULL").single().single()!!.toInt()

    private fun logPressSet(number: Int, weight: String, reps: String) {
        awaitDescription("Añadir serie a Press de banca")
        rule.onNodeWithContentDescription("Añadir serie a Press de banca").performScrollTo().performClick()
        awaitDescription("Peso de la serie $number, kilos")
        rule.onNodeWithContentDescription("Peso de la serie $number, kilos").performScrollTo().run { performTextClearance(); performTextInput(weight) }
        rule.onNodeWithContentDescription("Repeticiones de la serie $number").performScrollTo().run { performTextClearance(); performTextInput(reps) }
        rule.onNodeWithContentDescription("Marcar la serie $number como hecha").performScrollTo().performClick()
        awaitDescription("Serie $number hecha. Tocar para reabrirla")
    }

    private fun finishWorkout() {
        rule.onNode(verticalList).performScrollToNode(hasText("Terminar entrenamiento"))
        rule.onNodeWithText("Terminar entrenamiento").performClick()
        awaitText("Entrenamiento terminado")
        rule.onNodeWithText("Listo").performClick()
    }

    // ---- helpers over the visible screen ----

    private fun hasEntry(entry: String) = texts().any { it == entry }

    private fun awaitEntry(entry: String) {
        try {
            rule.waitUntil(TIMEOUT) { hasEntry(entry) }
        } catch (e: Throwable) {
            throw AssertionError("Never saw \"$entry\". Screen:\n" + dump(), e)
        }
    }

    private fun awaitEntryStartingWith(prefix: String) {
        try {
            rule.waitUntil(TIMEOUT) { texts().any { it.startsWith(prefix) } }
        } catch (e: Throwable) {
            throw AssertionError("Never saw an entry starting with \"$prefix\". Screen:\n" + dump(), e)
        }
    }

    private fun assertNoEntry(entry: String) = assertTrue("Unexpected \"$entry\" on screen:\n" + dump(), !hasEntry(entry))

    private fun openProgress(period: String? = null) {
        rule.onNodeWithText("Progreso").performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodesWithContentDescription("Cargando progreso").fetchSemanticsNodes().isEmpty() && hasEntry("PROGRESO") }
        if (period != null) {
            rule.onNode(verticalList).performScrollToNode(hasText("PERIODO"))
            if (!hasEntry("[cd] $period, seleccionado")) rule.onNodeWithContentDescription(period).performClick()
        }
    }

    private fun scrollTo(text: String) {
        rule.onNode(verticalList).performScrollToNode(hasText(text))
    }

    /** From Progress: opens a secondary route by its button. */
    private fun openFromProgress(button: String) {
        scrollTo(button)
        rule.onNodeWithText(button).performClick()
    }

    private fun tap(text: String) = rule.onNodeWithText(text, ignoreCase = true).performClick()

    private fun back() {
        rule.onNodeWithText("Atrás", ignoreCase = true).performClick()
    }

    private fun startRoutineLogAndFinish(weight: String, reps: String) {
        awaitDescription("Empezar la rutina Empuje")
        rule.onNodeWithContentDescription("Empezar la rutina Empuje").performScrollTo().performClick()
        awaitText("Press de banca")
        logPressSet(1, weight, reps)
        finishWorkout()
        rule.waitUntil(TIMEOUT) { finishedSessions() == 10 }
    }

    // ---- 1. logging + editing an OLD workout moves every derived value on screen ----

    @Test
    fun loggedWorkoutAndEditOfAnOldOneMoveProgressRecordsAndAchievementsConsistently() {
        startRoutineLogAndFinish("40", "5")

        // Progress, this week: the new 40 kg beats the old 10 kg, one session, 200 kg-rep.
        openProgress()
        awaitEntry("Récords de la semana | 1")
        awaitEntry("Sesiones | 1")
        awaitEntry("Volumen | 200")
        awaitEntry("Racha de días | 1")
        // All time: nine seeded sessions of 50 plus today's 200.
        rule.onNodeWithContentDescription("Todo").performClick()
        awaitEntry("Sesiones | 10")
        awaitEntry("Volumen | 650")

        // Achievements: the 10th workout unlocked "Calentando" and the PR is 40 kg.
        openFromProgress("Logros y récords")
        awaitEntryStartingWith("Calentando | 10 entrenos | Desbloqueado")
        scrollTo("RÉCORDS (PRS)")
        awaitEntryStartingWith("Press de banca | 40 kg")
        back()

        // Edit the OLDEST workout (10 -> 60 kg) from History.
        fun workoutEditMutations() = sql("SELECT payloadJson FROM outbox_mutations WHERE payloadJson LIKE '%loggedSets%' AND payloadJson LIKE '%\"set0\"%'").size
        val editMutationsBefore = workoutEditMutations()
        rule.onNodeWithText("Historial").performClick()
        awaitText("Entrenamiento libre")
        repeat(4) { rule.onNode(verticalList).performTouchInput { swipeUp() } }
        rule.onAllNodes(hasText("Entrenamiento libre") and hasClickAction()).onLast().performClick()
        awaitText("Editar entreno")
        rule.onNode(verticalList).performScrollToNode(hasText("Editar entreno"))
        rule.onNodeWithText("Editar entreno").performClick()
        val weight = "Peso de la serie 1 de Press de banca, kilos"
        awaitDescription(weight)
        rule.onNode(verticalList).performScrollToNode(hasContentDescription(weight))
        rule.onNodeWithContentDescription(weight).run { performTextClearance(); performTextInput("60") }
        rule.onNode(verticalList).performScrollToNode(hasText("Guardar cambios"))
        rule.onNodeWithText("Guardar cambios").performClick()
        rule.waitUntil(TIMEOUT) { sql("SELECT peso FROM logged_sets WHERE id = 'set0'").single().single()!!.toDouble() == 60.0 }

        // Everything derived followed ONE edit: all-time volume +250, the weekly record is gone (60 > 40 now),
        // the PR is 60 kg, and Press is stalled (nine sessions since the 60 kg one).
        openProgress("Todo")
        awaitEntry("Volumen | 900")
        awaitEntry("Sesiones | 10")
        awaitEntry("Récords de la semana | 0")
        scrollTo("EJERCICIOS ESTANCADOS")
        assertNoEntry("Ningún ejercicio estancado ahora mismo.")
        assertTrue("Press de banca is listed as stalled:\n" + dump(), texts().any { it.startsWith("Press de banca") && it.contains("sin mejorar") })
        openFromProgress("Logros y récords")
        scrollTo("RÉCORDS (PRS)")
        awaitEntryStartingWith("Press de banca | 60 kg")
        // The edit is EXACTLY one logical outbox mutation touching set0 (the achievement reconcile that follows a
        // local edit is a separate mutation on `achievements` only, so it does not count here).
        assertEquals("one logical mutation for the historical edit", editMutationsBefore + 1, workoutEditMutations())
        val edit = sql("SELECT payloadJson FROM outbox_mutations WHERE payloadJson LIKE '%\"set0\"%' ORDER BY sequence DESC LIMIT 1").single().single()!!
        assertTrue("the mutation carries only the changed set (unchanged rows are not rewritten) and no achievements", edit.contains("loggedSets") && !edit.contains("achievements"))
    }

    // ---- 2. body, gyms, theme, routine order, goal: all offline ----

    @Test
    fun bodyGymsThemeRoutineOrderAndGoalAreSavedOffline() {
        runBlocking { routines.create(RoutineInput("Tirón", "", listOf(RoutineExerciseInput("k-remo", "seed-remo", "Remo con barra")))) }
        val outboxBefore = sql("SELECT COUNT(*) FROM outbox_mutations").single().single()!!.toInt()

        // Body: first measurement of the default metric.
        openProgress()
        openFromProgress("Peso y medidas corporales")
        awaitText("Añadir medición")
        tap("Añadir medición")
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("80,5")
        tap("Guardar")
        rule.waitUntil(TIMEOUT) { sql("SELECT valor FROM body_metrics WHERE deletedAt IS NULL").map { it.single()!!.toDouble() } == listOf(80.5) }
        awaitTextContaining("80,5")
        back()

        // Settings -> Gyms: add, make default, archive.
        tap("Entrenar")
        awaitText("Ajustes")
        tap("Ajustes")
        awaitText("Gimnasios")
        tap("Gimnasios")
        awaitText("Añadir gimnasio")
        tap("Añadir gimnasio")
        rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Casa")
        tap("Guardar")
        rule.waitUntil(TIMEOUT) { sql("SELECT nombre FROM gyms WHERE deletedAt IS NULL ORDER BY orden").map { it.single() } == listOf("Fitness", "Casa") }
        awaitDescription("Usar Casa como predeterminado")
        rule.onNodeWithContentDescription("Usar Casa como predeterminado").performScrollTo().performClick()
        awaitTextContaining("Predeterminado")
        val casaId = sql("SELECT id FROM gyms WHERE nombre = 'Casa'").single().single()!!
        rule.waitUntil(TIMEOUT) { runBlocking { deviceSettings.observe().first().defaultGymId } == casaId }
        rule.onNodeWithContentDescription("Archivar Casa").performScrollTo().performClick()
        awaitTextContaining("Archivado")
        rule.waitUntil(TIMEOUT) { sql("SELECT archivada FROM gyms WHERE nombre = 'Casa'").single().single() == "1" }
        // An archived gym cannot stay the default (read back from the repository).
        rule.waitUntil(TIMEOUT) { runBlocking { deviceSettings.observe().first().defaultGymId } == null }
        back()

        // Settings -> Training: theme (device-only), weekly goal (synced), routine order (synced).
        tap("Entrenamiento y apariencia")
        awaitDescription("Oscuro")
        rule.onNodeWithContentDescription("Oscuro").performClick()
        rule.waitUntil(TIMEOUT) { runBlocking { deviceSettings.observe().first().theme } == ThemeMode.DARK }
        rule.onAllNodes(hasSetTextAction()).onFirst().run { performTextClearance(); performTextInput("4"); performImeAction() }
        rule.waitUntil(TIMEOUT) { sql("SELECT valor FROM user_settings WHERE id = 'objetivoSemanal'").singleOrNull()?.single() == "4" }
        rule.onNode(verticalList).performScrollToNode(hasContentDescription("Bajar Empuje"))
        rule.onNodeWithContentDescription("Bajar Empuje").performClick()
        rule.waitUntil(TIMEOUT) { sql("SELECT nombre FROM routines WHERE deletedAt IS NULL ORDER BY orden").map { it.single() } == listOf("Tirón", "Empuje") }

        // Device-only choices (theme, default gym) never reach the outbox; the synced ones did, as local mutations.
        val payloads = sql("SELECT payloadJson FROM outbox_mutations").map { it.single()!! }
        assertTrue("no outbox row mentions theme or default gym", payloads.none { p -> listOf("theme", "tema", "dark", "defaultGym", "predeterminado").any { p.contains(it, ignoreCase = true) } })
        assertTrue("body + gym + goal + routine order were queued locally", payloads.size > outboxBefore)
        assertTrue("body metric queued", payloads.any { it.contains("bodyMetrics") })
        assertTrue("goal queued", payloads.any { it.contains("objetivoSemanal") })
    }

    // ---- 3. backup export -> delete everything -> import, through the documents seam ----

    private val digestTables = listOf(
        "exercises", "routines", "routine_exercises", "workout_sessions", "logged_exercises", "logged_sets", "gyms",
        "exercise_photos", "user_settings", "coach_messages", "body_metrics", "progress_photos", "mesocycles", "achievements",
    )

    /** SHA-256 over every live row of the 14 synchronized tables, without versions (a merge keeps the file's own). */
    private fun contentDigest(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        for (table in digestTables) {
            val columns = sql("PRAGMA table_info($table)").map { it[1]!! }.filter { it != "updatedAt" && it != "serverVersion" }.sorted()
            md.update("#$table".toByteArray())
            sql("SELECT ${columns.joinToString { "\"$it\"" }} FROM $table WHERE deletedAt IS NULL ORDER BY id").forEach { md.update(it.joinToString("|").toByteArray()) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun rowsOf(table: String) = sql("SELECT COUNT(*) FROM $table WHERE deletedAt IS NULL").single().single()!!.toInt()

    @Test
    fun backupExportDeleteImportRestoresEverythingOffline() {
        val backup = BackupViewModel(backupRepository, documents, System::currentTimeMillis, { zone }, io = Dispatchers.Default)
        fun awaitPhase(what: String, ok: (BackupPhase) -> Boolean): BackupPhase = runBlocking {
            try { withTimeout(TIMEOUT) { backup.phase.first(ok) } } catch (e: Throwable) { throw AssertionError("$what: stuck in ${backup.phase.value}", e) }
        }
        // Rows in tables the seed does not fill: a body measurement and two synced settings.
        val sqlDb = db.openHelper.writableDatabase
        sqlDb.execSQL("INSERT INTO body_metrics (id, updatedAt, deletedAt, tipo, valor, fecha, serverVersion) VALUES ('bm1', 1, NULL, 'peso', 81.5, ${Instant.now().toEpochMilli()}, NULL)")
        sqlDb.execSQL("INSERT INTO user_settings (id, updatedAt, deletedAt, valor, serverVersion) VALUES ('objetivoSemanal', 1, NULL, '5', NULL)")
        sqlDb.execSQL("INSERT INTO user_settings (id, updatedAt, deletedAt, valor, serverVersion) VALUES ('modoProgresion', 1, NULL, '\"doble\"', NULL)")

        val digestBefore = contentDigest()
        assertEquals(9, rowsOf("workout_sessions"))
        assertEquals(1, rowsOf("body_metrics"))
        assertEquals(2, rowsOf("user_settings"))

        backup.exportTo("mem://backup.json")
        awaitPhase("export") { it is BackupPhase.Exported }
        val json = documents.files.getValue("mem://backup.json").decodeToString()
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        assertEquals("gymlog", (root["app"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue("schema version", (root["version"] as kotlinx.serialization.json.JsonPrimitive).content.toInt() in 1..12)
        assertTrue("exportedAt", (root["exportedAt"] as kotlinx.serialization.json.JsonPrimitive).content.toLong() > 0)
        val data = root["data"] as kotlinx.serialization.json.JsonObject
        assertEquals("the 14 synchronized tables", 14, data.size)
        assertEquals(9, (data["workoutSessions"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(1, (data["bodyMetrics"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals(2, (data["userSettings"] as kotlinx.serialization.json.JsonArray).size)
        assertTrue("no secrets or server metadata in the file", !json.contains("token", ignoreCase = true) && !json.contains("serverVersion"))

        // A fresh install: every table empty.
        db.clearAllTables()
        assertEquals(0, digestTables.sumOf { rowsOf(it) })
        assertEquals(0, sql("SELECT COUNT(*) FROM outbox_mutations").single().single()!!.toInt())

        backup.importFrom("mem://backup.json")
        val ready = awaitPhase("validate") { it is BackupPhase.Ready } as BackupPhase.Ready
        assertEquals(9, ready.preview.workouts)
        backup.selectMode(ImportMode.Merge)
        backup.startImport()
        awaitPhase("merge") { it is BackupPhase.Imported }
        assertEquals("merge restores the exact content of every table", digestBefore, contentDigest())
        assertTrue("the import was queued for sync", sql("SELECT COUNT(*) FROM outbox_mutations").single().single()!!.toInt() > 0)

        // Replace: an extra workout that is not in the file is removed; the outbox grows again.
        val queuedAfterMerge = sql("SELECT COUNT(*) FROM outbox_mutations").single().single()!!.toInt()
        runBlocking {
            val at = Instant.now().toEpochMilli()
            db.workoutSessionDao().upsert(WorkoutSessionEntity("extra", 99_999_999_999_999L, null, null, null, null, at, 600, null, null))
        }
        assertEquals(10, rowsOf("workout_sessions"))
        backup.importFrom("mem://backup.json")
        awaitPhase("validate again") { it is BackupPhase.Ready }
        backup.selectMode(ImportMode.ReplaceAfterConfirmation)
        backup.startImport()
        awaitPhase("confirm") { it is BackupPhase.ConfirmReplace }
        backup.confirmReplace()
        awaitPhase("replace") { it is BackupPhase.Imported }
        assertEquals("replace leaves exactly the file's content", digestBefore, contentDigest())
        assertTrue("the replace tombstone was queued", sql("SELECT COUNT(*) FROM outbox_mutations").single().single()!!.toInt() > queuedAfterMerge)
    }

    private companion object {
        const val USER = "user_e2e"
        const val TIMEOUT = 15_000L
    }
}

