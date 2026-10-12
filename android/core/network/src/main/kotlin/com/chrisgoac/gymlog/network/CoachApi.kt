package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.ResponseJson
import com.chrisgoac.gymlog.model.ai.AiReportCategory
import com.chrisgoac.gymlog.model.ai.CoachRequestDto
import com.chrisgoac.gymlog.model.ai.ReportResponseDto
import com.chrisgoac.gymlog.model.ai.ReportResponseRequestDto
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One line of the Coach's `application/x-ndjson` stream (`AiStreamEventSchema`). */
sealed interface CoachStreamEvent {
    data class Start(val clientMessageId: String, val responseId: String) : CoachStreamEvent

    data class Delta(val text: String) : CoachStreamEvent

    /** [disclaimer]: the answer is general guidance, not medical advice; the client shows the disclaimer with it. */
    data class Complete(val responseId: String, val disclaimer: Boolean) : CoachStreamEvent

    /** The attempt failed after `start`; deltas already received are not a response. */
    data class Error(val code: String, val retryable: Boolean) : CoachStreamEvent
}

/**
 * The Pro Coach endpoints (`/api/v1/ai/…`). Like the other v1 clients every call names the account it is
 * made for and is sent only with that account's token. Nothing here logs: user text, answers and tokens
 * must never reach logs.
 */
interface CoachApi {
    /**
     * Sends one Coach turn and hands every stream event to [onEvent] as it arrives, in order. Returns when
     * the body ends (with or without a terminal event: the caller decides whether it was complete). A JSON
     * error before the stream (403 `PRO_REQUIRED`, 409 `AI_RESPONSE_IN_PROGRESS`, 503, 429…) is a
     * [SyncApiException.Api]; a cut connection is [SyncApiException.Transport]; a line that is not a valid
     * event is [SyncApiException.Malformed]. What [onEvent] throws is rethrown unchanged.
     */
    suspend fun coach(expectedUserId: String, request: CoachRequestDto, onEvent: suspend (CoachStreamEvent) -> Unit)

    /** Reports a response the user owns; repeating a report is idempotent (`created = false`). */
    suspend fun report(expectedUserId: String, responseId: String, category: AiReportCategory, context: String?): ReportResponseDto
}

class CoachApiImpl internal constructor(
    client: HttpClient,
    baseUrl: String,
    auth: AuthSessionProvider,
    serverClock: ServerClock,
    requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : CoachApi {
    private val root = baseUrl.trimEnd('/')
    private val calls = AuthorizedCalls(client, auth, serverClock, requestIdFactory)

    override suspend fun coach(expectedUserId: String, request: CoachRequestDto, onEvent: suspend (CoachStreamEvent) -> Unit) {
        val body = ContractJson.encodeToString(CoachRequestDto.serializer(), request)
        calls.stream(
            expectedUserId,
            accept = "$NDJSON, ${ContentType.Application.Json}",
            configure = {
                method = HttpMethod.Post
                url("$root/api/v1/ai/coach")
                setBody(TextContent(body, ContentType.Application.Json))
                // The server cuts the model at 45 s inside a 60 s route budget; tokens may pause a while.
                timeout {
                    requestTimeoutMillis = STREAM_REQUEST_TIMEOUT_MILLIS
                    socketTimeoutMillis = STREAM_SOCKET_TIMEOUT_MILLIS
                }
            },
        ) { response ->
            val channel = response.bodyAsChannel()
            val lines = NdjsonLines()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read == -1) break
                for (line in lines.feed(buffer, read)) deliver(CoachStreamParser.parse(line), onEvent)
            }
            // A last line without its newline: kept if it is a whole event, dropped if the cut truncated it.
            lines.finish()?.let { rest -> deliver(runCatching { CoachStreamParser.parse(rest) }.getOrNull(), onEvent) }
        }
    }

    private suspend fun deliver(event: CoachStreamEvent?, onEvent: suspend (CoachStreamEvent) -> Unit) {
        if (event == null) return
        try {
            onEvent(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AuthorizedCalls.DownstreamFailure(e)
        }
    }

    override suspend fun report(expectedUserId: String, responseId: String, category: AiReportCategory, context: String?): ReportResponseDto {
        val body = ReportJson.encodeToString(ReportResponseRequestDto.serializer(), ReportResponseRequestDto(category.wire, context))
        return calls.execute(
            expectedUserId,
            configure = {
                method = HttpMethod.Post
                url("$root/api/v1/ai/responses/${responseId.encodeURLPathPart()}/report")
                setBody(TextContent(body, ContentType.Application.Json))
            },
            decode = { ResponseJson.decodeFromString(ReportResponseDto.serializer(), it) },
        )
    }

    companion object {
        const val NDJSON = "application/x-ndjson"
        private const val STREAM_REQUEST_TIMEOUT_MILLIS = 75_000L
        private const val STREAM_SOCKET_TIMEOUT_MILLIS = 60_000L
        private const val BUFFER_BYTES = 8 * 1024

        /** The optional `context` is omitted when absent (the server's schema has no null). */
        private val ReportJson = Json(from = ContractJson) { explicitNulls = false }
    }
}

/**
 * Splits a byte stream into lines at `\n`, whatever the chunk boundaries: a line is decoded as UTF-8 only
 * once complete, so a multi-byte character split across chunks is never broken. A trailing `\r` is
 * dropped, blank lines are skipped. A single line longer than [maxLineBytes] is [SyncApiException.Malformed].
 */
class NdjsonLines(private val maxLineBytes: Int = MAX_LINE_BYTES) {
    private val pending = ByteArrayOutputStream()

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        for (i in 0 until length) {
            if (bytes[i] == NEWLINE) {
                pending.write(bytes, start, i - start)
                start = i + 1
                takeLine()?.let(lines::add)
            }
        }
        pending.write(bytes, start, length - start)
        if (pending.size() > maxLineBytes) throw SyncApiException.Malformed("Stream line exceeds $maxLineBytes bytes")
        return lines
    }

    /** What is left after the last newline (null when nothing). */
    fun finish(): String? = takeLine()

    private fun takeLine(): String? {
        val text = pending.toByteArray().decodeToString().removeSuffix("\r")
        pending.reset()
        return text.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()

        /** A delta is ≤ 8000 chars (≤ 32 KiB of UTF-8) plus its JSON envelope. */
        const val MAX_LINE_BYTES = 256 * 1024
    }
}

/** Decodes one stream line. Unknown event types are ignored (null), so a newer server's events never break the stream. */
object CoachStreamParser {
    fun parse(line: String): CoachStreamEvent? {
        val obj = try {
            Json.parseToJsonElement(line) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw SyncApiException.Malformed("Stream line is not a JSON object")
        return when (obj.string("type")) {
            "start" -> CoachStreamEvent.Start(obj.require("clientMessageId"), obj.require("responseId"))
            "delta" -> CoachStreamEvent.Delta(obj.require("text"))
            "complete" -> CoachStreamEvent.Complete(obj.require("responseId"), obj.bool("disclaimer") ?: false)
            "error" -> CoachStreamEvent.Error(obj.require("code"), obj.bool("retryable") ?: false)
            else -> null
        }
    }

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(name: String): Boolean? = (get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.require(name: String): String =
        string(name) ?: throw SyncApiException.Malformed("Stream event without a string '$name'")
}

/** Production factory (OkHttp engine); share the app's [serverClock]. */
fun createCoachApi(baseUrl: String, auth: AuthSessionProvider, serverClock: ServerClock): CoachApi =
    CoachApiImpl(createGymLogHttpClient(), baseUrl, auth, serverClock)
