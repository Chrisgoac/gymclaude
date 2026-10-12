package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.ResponseJson
import com.chrisgoac.gymlog.model.ai.IntelligentReportRequestDto
import com.chrisgoac.gymlog.model.ai.IntelligentReportResponseDto
import com.chrisgoac.gymlog.model.ai.MesocycleRequestDto
import com.chrisgoac.gymlog.model.ai.MesocycleResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import java.util.UUID

/**
 * The Pro planning endpoints: mesocycle drafts and intelligent reports (`/api/v1/ai/mesocycles`,
 * `/api/v1/ai/reports`). Every call names the account it is made for. A JSON error (403 `PRO_REQUIRED`,
 * 409, 429, 502 `AI_BAD_OUTPUT`, 503…) is a [SyncApiException.Api]; an undecodable 2xx body is
 * [SyncApiException.Malformed]. Nothing here logs.
 */
interface AiPlanApi {
    /** A validated mesocycle draft; retrying with the same `clientRequestId` returns the same draft. */
    suspend fun generateMesocycle(expectedUserId: String, request: MesocycleRequestDto): MesocycleResponseDto

    /** The report for this snapshot: the server's cached one when the source is unchanged, else a new one. */
    suspend fun intelligentReport(expectedUserId: String, request: IntelligentReportRequestDto): IntelligentReportResponseDto
}

class AiPlanApiImpl internal constructor(
    client: HttpClient,
    baseUrl: String,
    auth: AuthSessionProvider,
    serverClock: ServerClock,
    requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : AiPlanApi {
    private val root = baseUrl.trimEnd('/')
    private val calls = AuthorizedCalls(client, auth, serverClock, requestIdFactory)

    override suspend fun generateMesocycle(expectedUserId: String, request: MesocycleRequestDto): MesocycleResponseDto =
        post(expectedUserId, "mesocycles", ContractJson.encodeToString(MesocycleRequestDto.serializer(), request), MESOCYCLE_TIMEOUT_MILLIS) {
            ResponseJson.decodeFromString(MesocycleResponseDto.serializer(), it)
        }

    override suspend fun intelligentReport(expectedUserId: String, request: IntelligentReportRequestDto): IntelligentReportResponseDto =
        post(expectedUserId, "reports", ContractJson.encodeToString(IntelligentReportRequestDto.serializer(), request), REPORT_TIMEOUT_MILLIS) {
            ResponseJson.decodeFromString(IntelligentReportResponseDto.serializer(), it)
        }

    private suspend fun <T> post(expectedUserId: String, path: String, body: String, timeoutMillis: Long, decode: (String) -> T): T =
        calls.execute(
            expectedUserId,
            configure = {
                method = HttpMethod.Post
                url("$root/api/v1/ai/$path")
                setBody(TextContent(body, ContentType.Application.Json))
                // The server cuts the model at 90 s (mesocycle) / 60 s (report); the answer comes in one piece.
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    socketTimeoutMillis = timeoutMillis
                }
            },
            decode = decode,
        )

    private companion object {
        const val MESOCYCLE_TIMEOUT_MILLIS = 125_000L
        const val REPORT_TIMEOUT_MILLIS = 95_000L
    }
}

/** Production factory (OkHttp engine); share the app's [serverClock]. */
fun createAiPlanApi(baseUrl: String, auth: AuthSessionProvider, serverClock: ServerClock): AiPlanApi =
    AiPlanApiImpl(createGymLogHttpClient(), baseUrl, auth, serverClock)
