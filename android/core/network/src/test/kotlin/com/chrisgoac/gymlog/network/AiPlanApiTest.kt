package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ai.CatalogEntryDto
import com.chrisgoac.gymlog.model.ai.CoachSnapshotDto
import com.chrisgoac.gymlog.model.ai.IntelligentReportRequestDto
import com.chrisgoac.gymlog.model.ai.MesocycleParamsDto
import com.chrisgoac.gymlog.model.ai.MesocycleRequestDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiPlanApiTest {
    private class FakeAuth : AuthSessionProvider {
        override val state = MutableStateFlow<AuthState>(AuthState.SignedIn(USER))
        override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String? =
            if (expectedUserId != USER) null else if (forceRefresh) "tok-2" else "tok-1"
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(
        requests: MutableList<HttpRequestData> = mutableListOf(),
        bodies: MutableList<String> = mutableListOf(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = AiPlanApiImpl(
        createGymLogHttpClient(
            MockEngine { request ->
                requests += request
                bodies += request.body.toByteArray().decodeToString()
                handler(request)
            },
        ),
        "https://example.test/",
        FakeAuth(),
        ServerClock(),
        requestIdFactory = { "req-1" },
    )

    private val snapshot = CoachSnapshotDto(
        estancados = emptyList(),
        semana = CoachSnapshotDto.Semana(2, 3, 5000, null, emptyList()),
        grupos = emptyList(),
        cuerpo = CoachSnapshotDto.Cuerpo(null, emptyList()),
    )
    private val mesoRequest = MesocycleRequestDto(
        CLIENT_ID,
        MesocycleParamsDto("fuerza", 3, 6, 60),
        snapshot,
        listOf(CatalogEntryDto("Press banca", "pecho", "barra")),
    )

    @Test
    fun postsTheMesocycleRequestStrictlyAndDecodesTheDraft() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val api = api(requests, bodies) {
            respond(
                """{"clientRequestId":"$CLIENT_ID","responseId":"r1","omitidos":["X"],"disclaimer":true,"future":1,
                   "draft":{"nombre":"B","objetivo":"fuerza","semanas":4,"diasPorSemana":2,"notas":null,
                   "progresion":[{"semana":1,"descarga":false,"ajuste":"base"}],
                   "dias":[{"nombre":"A","ejercicios":[{"nombre":"Press banca","seriesObjetivo":4,"repsObjetivo":6,"descansoSegundos":180}]}]}}""",
                HttpStatusCode.OK,
                json,
            )
        }
        val out = api.generateMesocycle(USER, mesoRequest)
        assertEquals("r1", out.responseId)
        assertEquals(listOf("X"), out.omitidos)
        assertEquals(180, out.draft.dias.single().ejercicios.single().descansoSegundos)

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://example.test/api/v1/ai/mesocycles", request.url.toString())
        assertEquals("Bearer tok-1", request.headers[HttpHeaders.Authorization])
        val body = Json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals(setOf("clientRequestId", "params", "snapshot", "catalogo"), body.keys)
        // The server's snapshot schema needs nullable keys present.
        assertTrue(bodies.single().contains("\"peso\":null"))
    }

    @Test
    fun postsTheReportSnapshotAndDecodesTheReport() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        val api = api(requests, bodies) {
            respond(
                """{"reportId":"rep","generatedAt":5,"cached":true,"disclaimer":true,
                   "report":{"resumen":"S","puntosFuertes":[],"aMejorar":["a"],"recomendaciones":["r"]}}""",
                HttpStatusCode.OK,
                json,
            )
        }
        val out = api.intelligentReport(USER, IntelligentReportRequestDto(snapshot))
        assertTrue(out.cached)
        assertEquals(listOf("r"), out.report.recomendaciones)
        assertEquals("https://example.test/api/v1/ai/reports", requests.single().url.toString())
        assertEquals(setOf("snapshot"), Json.parseToJsonElement(bodies.single()).jsonObject.keys)
    }

    @Test
    fun proRequiredIsAnApiErrorNotASessionProblem() = runTest {
        val api = api {
            respond("""{"error":{"code":"PRO_REQUIRED","message":"m","retryable":false}}""", HttpStatusCode.Forbidden, json)
        }
        try {
            api.generateMesocycle(USER, mesoRequest)
            fail("expected Api")
        } catch (e: SyncApiException.Api) {
            assertEquals("PRO_REQUIRED", e.rawCode)
            assertFalse(e.retryable)
        }
    }

    @Test
    fun aPlain403IsStillASessionFailure() = runTest {
        val api = api { respond("", HttpStatusCode.Forbidden) }
        try {
            api.intelligentReport(USER, IntelligentReportRequestDto(snapshot))
            fail("expected SessionRejected")
        } catch (_: SyncApiException.SessionRejected) {
        }
    }

    @Test
    fun badModelOutputIsARetryableApiErrorAndAnUndecodableBodyIsMalformed() = runTest {
        val bad = api { respond("""{"error":{"code":"AI_BAD_OUTPUT","message":"m","retryable":true}}""", HttpStatusCode.BadGateway, json) }
        try {
            bad.generateMesocycle(USER, mesoRequest)
            fail("expected Api")
        } catch (e: SyncApiException.Api) {
            assertEquals(502, e.httpStatus)
            assertEquals("AI_BAD_OUTPUT", e.rawCode)
            assertTrue(e.retryable)
        }
        val garbled = api { respond("""{"reportId":1}""", HttpStatusCode.OK, json) }
        try {
            garbled.intelligentReport(USER, IntelligentReportRequestDto(snapshot))
            fail("expected Malformed")
        } catch (_: SyncApiException.Malformed) {
        }
    }

    @Test
    fun a401IsRetriedOnceWithAFreshTokenAndTheSameBody() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<String>()
        var first = true
        val api = api(requests, bodies) {
            if (first) {
                first = false
                respond("", HttpStatusCode.Unauthorized)
            } else {
                respond(
                    """{"reportId":"rep","generatedAt":5,"cached":false,"disclaimer":true,
                       "report":{"resumen":"S","puntosFuertes":[],"aMejorar":[],"recomendaciones":["r"]}}""",
                    HttpStatusCode.OK,
                    json,
                )
            }
        }
        api.intelligentReport(USER, IntelligentReportRequestDto(snapshot))
        assertEquals(listOf("Bearer tok-1", "Bearer tok-2"), requests.map { it.headers[HttpHeaders.Authorization] })
        assertEquals(bodies[0], bodies[1])
    }

    private companion object {
        const val USER = "user_1"
        const val CLIENT_ID = "1b4e28ba-2fa1-4d2b-883f-0016d3cca427"
    }
}
