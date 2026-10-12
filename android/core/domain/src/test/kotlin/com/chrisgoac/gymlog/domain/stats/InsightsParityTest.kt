package com.chrisgoac.gymlog.domain.stats

import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.math.BodyEntry
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.domain.time.Periods
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Runs contracts/domain/insight-cases.json (generated from the PWA's stats code by
 * scripts/export-domain-fixtures.mjs) against [WorkoutStats] and [Insights]. Dataset rows include
 * tombstones; [rowsOf] keeps only live rows whose parents are live, the contract every
 * [StatRows] producer (the Room repository's SQL) must honour.
 */
class InsightsParityTest {
    private val fixture: JsonObject = run {
        val dir = File(checkNotNull(System.getProperty("gymlog.domainContractsDir")) { "gymlog.domainContractsDir not set" })
        Json.parseToJsonElement(File(dir, "insight-cases.json").readText()).jsonObject
    }

    private fun JsonObject.cases(key: String): List<JsonObject> =
        getValue(key).jsonArray.map { it.jsonObject }.also { assertTrue("$key has cases", it.isNotEmpty()) }

    private fun JsonElement.longOrNull(): Long? = if (this is JsonNull) null else jsonPrimitive.long
    private fun JsonElement.intOrNull(): Int? = if (this is JsonNull) null else jsonPrimitive.int
    private fun JsonElement.strOrNull(): String? = if (this is JsonNull) null else jsonPrimitive.content
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.double
    private fun group(wire: String) = MuscleGroup.entries.first { it.wire == wire }

    private fun assertClose(message: String, expected: Double, actual: Double) = assertEquals(message, expected, actual, 1e-9)

    @Test
    fun gymFilter() {
        for (c in fixture.cases("gymFilter")) {
            assertEquals("$c", c.getValue("gymId").strOrNull(), GymFilter.fromWire(c.str("filter")).gymId)
        }
        assertEquals(GymFilter.All, GymFilter.fromWire(null))
    }

    @Test
    fun deloadAdvice() = assertEquals(fixture.str("deloadAdvice"), Insights.DELOAD_ADVICE)

    @Test
    fun stalls() {
        for (c in fixture.cases("stalls")) {
            val points = c.getValue("mejor1RM").jsonArray.mapIndexed { i, rm -> ProgressPoint(1000L + i, 0.0, rm.jsonPrimitive.double, 0.0) }
            val e = c.getValue("expected").jsonObject
            assertEquals(
                "$c",
                StallDetection(e.getValue("estancado").jsonPrimitive.boolean, e.getValue("sesionesSinMejora").jsonPrimitive.int, e.getValue("ultimaMejoraFecha").longOrNull()),
                Insights.detectStall(points, c.getValue("n").jsonPrimitive.int),
            )
        }
    }

    @Test
    fun weeklyVolumeDeltas() {
        for (c in fixture.cases("weeklyVolumeDeltas")) {
            val points = c.getValue("volumes").jsonArray.mapIndexed { i, v -> WeeklyVolume(i.toLong(), v.jsonPrimitive.double) }
            assertEquals("$c", c.getValue("expected").jsonArray.map { it.intOrNull() }, WorkoutStats.weeklyVolumeDeltas(points).map { it.deltaPct })
        }
    }

    /** Live rows only, children of tombstoned (or missing) parents dropped, denormalized like the repository's joins. */
    private fun rowsOf(c: JsonObject): StatRows {
        fun JsonObject.live() = getValue("deletedAt") is JsonNull
        val sessions = c.getValue("sessions").jsonArray.map { it.jsonObject }.filter { it.live() }
            .map { StatSession(it.str("id"), it.getValue("fecha").jsonPrimitive.long, it.getValue("gymId").strOrNull()) }
        val sessionById = sessions.associateBy { it.id }
        val exercises = c.getValue("loggedExercises").jsonArray.map { it.jsonObject }.filter { it.live() }.mapNotNull { le ->
            val session = sessionById[le.str("sessionId")] ?: return@mapNotNull null
            StatExercise(le.str("id"), session.id, le.str("exerciseId"), session.startedAt, session.gymId)
        }
        val exerciseById = exercises.associateBy { it.id }
        val sets = c.getValue("sets").jsonArray.map { it.jsonObject }.filter { it.live() }.mapNotNull { s ->
            val le = exerciseById[s.str("loggedExerciseId")] ?: return@mapNotNull null
            StatSet(
                s.str("id"), le.id, le.sessionId, le.exerciseId, le.startedAt, le.gymId,
                s.num("peso"), s.getValue("reps").jsonPrimitive.int, s.getValue("esCalentamiento").jsonPrimitive.boolean,
            )
        }
        return StatRows(sessions, exercises, sets)
    }

    private fun infosOf(c: JsonObject): Map<String, ExerciseInfo> = c.getValue("exercises").jsonArray.map { it.jsonObject }.associate {
        // Deleted exercises keep resolving (the PWA's bulkGet ignores deletedAt).
        it.str("id") to ExerciseInfo(it.str("nombre"), MuscleGroup.entries.firstOrNull { g -> g.wire == it.str("grupoMuscular") })
    }

    private fun points(json: JsonArray) = json.map { it.jsonObject }.map {
        ProgressPoint(it.getValue("fecha").jsonPrimitive.long, it.num("maxPeso"), it.num("mejor1RM"), it.num("volumen"))
    }

    private fun assertPoints(message: String, expected: List<ProgressPoint>, actual: List<ProgressPoint>) {
        assertEquals("$message size", expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(message, e.copy(volume = 0.0), a.copy(volume = 0.0))
            assertClose("$message volume", e.volume, a.volume)
        }
    }

    @Test
    fun datasets() {
        val datasets = fixture.cases("datasets")
        for (c in datasets) {
            val name = c.str("name")
            val rows = rowsOf(c)
            val infos = infosOf(c)
            val now = Instant.ofEpochMilli(c.getValue("now").jsonPrimitive.long)
            val zone = ZoneId.of(c.str("zone"))
            val goals = c.getValue("objetivosVolumen").jsonObject.mapKeys { group(it.key) }.mapValues { it.value.jsonPrimitive.double }
            val expected = c.getValue("expected").jsonObject

            for (g in expected.getValue("byGym").jsonArray.map { it.jsonObject }) {
                val gym = GymFilter.fromWire(g.getValue("gymId").strOrNull() ?: GymFilter.ALL_WIRE)
                val at = "$name / $gym"

                val ws = g.getValue("weeklySummary").jsonObject
                val summary = WorkoutStats.weeklySummary(rows, gym, now, zone)
                assertEquals("$at weekly sessions", ws.getValue("sesiones").jsonPrimitive.int, summary.sessions)
                assertClose("$at weekly volume", ws.num("volumenSemana"), summary.volume)
                assertClose("$at weekly previous", ws.num("volumenSemanaPrevia"), summary.previousVolume)
                assertEquals("$at weekly delta", ws.getValue("deltaPct").intOrNull(), summary.deltaPct)

                assertEquals(
                    "$at week records",
                    g.getValue("prsThisWeek").jsonArray.map { it.jsonObject }.map { p ->
                        WeekRecord(p.str("exerciseId"), p.str("nombre"), RecordKind.entries.first { it.wire == p.str("tipo") })
                    },
                    WorkoutStats.weekRecords(rows, infos, gym, now, zone),
                )

                val weekVolume = WorkoutStats.weekVolumeByMuscle(rows, infos, gym, now, zone)
                g.getValue("volumenSemanaByMuscle").jsonObject.forEach { (k, v) -> assertClose("$at week volume $k", v.jsonPrimitive.double, weekVolume.getValue(group(k))) }
                assertEquals("$at week volume groups", MuscleGroup.entries.toSet(), weekVolume.keys)

                val lastTrained = WorkoutStats.lastTrainedByMuscle(rows, infos, gym)
                assertEquals(
                    "$at last trained",
                    g.getValue("lastTrainedByMuscle").jsonObject.entries.associate { (k, v) -> group(k) to v.longOrNull() },
                    lastTrained,
                )

                assertEquals(
                    "$at stalls",
                    g.getValue("estancados").jsonArray.map { it.jsonObject }.map {
                        Stall(it.str("exerciseId"), it.str("nombre"), it.getValue("sesionesSinMejora").jsonPrimitive.int, it.getValue("ultimaMejoraFecha").longOrNull())
                    },
                    WorkoutStats.stalls(rows, infos, gym),
                )

                for (p in g.getValue("periods").jsonArray.map { it.jsonObject }) {
                    val period = Period.fromWire(p.str("periodo"))!!
                    val since = Periods.since(period, now).toEpochMilli()
                    val pat = "$at ${period.wire}"
                    assertEquals("$pat since", p.getValue("since").jsonPrimitive.long, since)

                    val ps = p.getValue("periodSummary").jsonObject
                    val periodSummary = WorkoutStats.periodSummary(rows, since, gym)
                    assertEquals("$pat sessions", ps.getValue("sesiones").jsonPrimitive.int, periodSummary.sessions)
                    assertClose("$pat volume", ps.num("volumen"), periodSummary.volume)

                    val weekly = WorkoutStats.weeklyVolumeDeltas(WorkoutStats.weeklyVolume(rows, since, gym, zone))
                    val expectedWeekly = p.getValue("weeklyVolume").jsonArray.map { it.jsonObject }
                    assertEquals("$pat weeks", expectedWeekly.map { it.getValue("semanaInicioTs").jsonPrimitive.long }, weekly.map { it.weekStart })
                    assertEquals("$pat week deltas", expectedWeekly.map { it.getValue("deltaPct").intOrNull() }, weekly.map { it.deltaPct })
                    expectedWeekly.zip(weekly).forEach { (e, a) -> assertClose("$pat week volume", e.num("volumen"), a.volume) }

                    val byMuscle = WorkoutStats.volumeByMuscle(rows, infos, since, gym)
                    val expectedByMuscle = p.getValue("volumeByMuscle").jsonArray.map { it.jsonObject }
                    assertEquals("$pat muscle order", expectedByMuscle.map { group(it.str("grupo")) }, byMuscle.map { it.group })
                    expectedByMuscle.zip(byMuscle).forEach { (e, a) -> assertClose("$pat muscle volume", e.num("volumen"), a.volume) }

                    val balance = Insights.balance(byMuscle, lastTrained, now.toEpochMilli(), goals, weekVolume)
                    val expectedBalance = p.getValue("balance").jsonArray.map { it.jsonObject }
                    assertEquals("$pat balance size", expectedBalance.size, balance.size)
                    expectedBalance.zip(balance).forEach { (e, a) ->
                        assertEquals("$pat balance", group(e.str("grupo")), a.group)
                        assertClose("$pat balance volume", e.num("volumen"), a.volume)
                        assertEquals("$pat balance days", e.getValue("diasSinEntrenar").longOrNull(), a.daysSinceTrained)
                        assertEquals("$pat balance neglected", e.getValue("descuidado").jsonPrimitive.boolean, a.neglected)
                        assertEquals("$pat balance goal", e.getValue("metaPct").intOrNull(), a.goalPct)
                    }
                }

                for (e in g.getValue("exercises").jsonArray.map { it.jsonObject }) {
                    val exerciseId = e.str("exerciseId")
                    val prs = e.getValue("prs")
                    val records = WorkoutStats.exerciseRecords(rows, exerciseId, gym)
                    if (prs is JsonNull) {
                        assertEquals("$at $exerciseId records", null, records)
                    } else {
                        assertEquals("$at $exerciseId records", ExerciseRecords(prs.jsonObject.num("maxPeso"), prs.jsonObject.num("mejor1RM")), records)
                    }
                    for (p in e.getValue("progress").jsonArray.map { it.jsonObject }) {
                        val since = Periods.since(Period.fromWire(p.str("periodo"))!!, now).toEpochMilli()
                        assertPoints("$at $exerciseId ${p.str("periodo")}", points(p.getValue("points").jsonArray), WorkoutStats.exerciseProgress(rows, exerciseId, gym, since))
                    }
                }
            }

            val starts = rows.sessions.map { it.startedAt }
            assertEquals("$name day streak", expected.getValue("currentStreakDays").jsonPrimitive.int, WorkoutStats.dailyStreak(starts, now, zone))
            for (r in expected.getValue("racha").jsonArray.map { it.jsonObject }) {
                assertEquals(
                    "$name week streak $r",
                    WeeklyStreak(r.getValue("actual").jsonPrimitive.int, r.getValue("mejor").jsonPrimitive.int),
                    WorkoutStats.weeklyStreak(starts, r.getValue("objetivo").jsonPrimitive.int, now, zone),
                )
            }
        }
        // Guard that the fixture still covers what it promises.
        val names = datasets.map { it.str("name") }
        assertTrue(names.containsAll(listOf("empty", "one set", "history")) && names.count { it.startsWith("boundary @") } >= 3)
    }

    @Test
    fun coachSnapshot() {
        for (c in fixture.cases("coachSnapshot")) {
            val i = c.getValue("input").jsonObject
            val semana = i.getValue("semana").jsonObject
            fun groupMap(key: String) = i.getValue(key).jsonObject.entries.associate { (k, v) -> group(k) to v }
            val input = CoachSnapshotInput(
                stalls = i.getValue("estancados").jsonArray.map { it.jsonObject }.map {
                    Stall(it.str("exerciseId"), it.str("nombre"), it.getValue("sesionesSinMejora").jsonPrimitive.int, it.getValue("ultimaMejoraFecha").longOrNull())
                },
                week = WeeklySummary(semana.getValue("sesiones").jsonPrimitive.int, semana.num("volumenSemana"), semana.num("volumenSemanaPrevia"), semana.getValue("deltaPct").intOrNull()),
                weeklyTarget = i.getValue("objetivoSemanal").jsonPrimitive.int,
                weekRecords = i.getValue("prs").jsonArray.map { it.jsonObject }.map { p ->
                    WeekRecord(p.str("exerciseId"), p.str("nombre"), RecordKind.entries.first { it.wire == p.str("tipo") })
                },
                weekVolumeByGroup = groupMap("volumenSemanaPorGrupo").mapValues { it.value.jsonPrimitive.double },
                lastTrained = groupMap("lastTrained").mapValues { it.value.longOrNull() },
                volumeTargets = groupMap("objetivosVolumen").mapValues { it.value.jsonPrimitive.double },
                now = i.getValue("ahora").jsonPrimitive.long,
                body = i.getValue("cuerpo").jsonArray.map { it.jsonObject }.map { s ->
                    BodySeries(s.str("tipo"), s.str("label"), s.getValue("entradas").jsonArray.map { it.jsonObject }.map {
                        BodyEntry(it.num("valor"), Instant.ofEpochMilli(it.getValue("fecha").jsonPrimitive.long))
                    })
                },
            )
            val e = c.getValue("expected").jsonObject
            val ew = e.getValue("semana").jsonObject
            val body = e.getValue("cuerpo").jsonObject
            fun measure(m: JsonObject?) = m?.let { CoachSnapshot.Weight(it.num("actual"), it.getValue("delta4sem").let { d -> if (d is JsonNull) null else d.jsonPrimitive.double }) }
            val expected = CoachSnapshot(
                stalls = e.getValue("estancados").jsonArray.map { it.jsonObject }.map { CoachSnapshot.Line(it.str("ejercicio"), it.getValue("sesionesSinMejora").jsonPrimitive.int) },
                week = CoachSnapshot.Week(
                    ew.getValue("sesiones").jsonPrimitive.int, ew.getValue("objetivo").jsonPrimitive.int, ew.getValue("volumen").jsonPrimitive.long,
                    ew.getValue("deltaPct").intOrNull(),
                    ew.getValue("prs").jsonArray.map { it.jsonObject }.map { p -> CoachSnapshot.Record(p.str("ejercicio"), RecordKind.entries.first { it.wire == p.str("tipo") }) },
                ),
                groups = e.getValue("grupos").jsonArray.map { it.jsonObject }.map {
                    CoachSnapshot.Group(
                        it.str("grupo"), it.getValue("volumenSemana").jsonPrimitive.long, it.getValue("diasSinEntrenar").longOrNull(),
                        it.getValue("objetivo").let { o -> if (o is JsonNull) null else o.jsonPrimitive.double },
                    )
                },
                body = CoachSnapshot.Body(
                    weight = measure(body.getValue("peso").let { if (it is JsonNull) null else it.jsonObject }),
                    measures = body.getValue("medidas").jsonArray.map { it.jsonObject }.map {
                        val m = measure(it)!!
                        CoachSnapshot.Measure(it.str("metrica"), m.current, m.delta4Weeks)
                    },
                ),
            )
            assertEquals(c.str("name"), expected, Insights.coachSnapshot(input))
        }
    }
}
