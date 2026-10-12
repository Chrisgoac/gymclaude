package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.model.ResponseJson
import com.chrisgoac.gymlog.model.media.MediaDeleteResponse
import com.chrisgoac.gymlog.model.media.MediaKind
import com.chrisgoac.gymlog.model.media.MediaLimits
import com.chrisgoac.gymlog.model.media.MediaReadGrant
import com.chrisgoac.gymlog.model.media.MediaUploadResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * The private media endpoints (`/api/v1/media/…`). Like [SyncApi], every authenticated call names the
 * account it is made for and is sent only with that account's token.
 *
 * [download] fetches a signed read URL: it is sent WITHOUT the bearer token (the URL itself is the
 * short-lived credential, and it points at object storage, not at the API).
 */
interface MediaApi {
    /**
     * Uploads [file] (JPEG) as photo [mediaId] with the owner [fields]. Retrying with the same
     * [mediaId] returns the original object instead of storing a second one.
     */
    suspend fun upload(
        expectedUserId: String,
        kind: MediaKind,
        mediaId: String,
        fields: Map<String, String>,
        file: File,
        mimeType: String = "image/jpeg",
    ): MediaUploadResponse

    suspend fun readGrant(expectedUserId: String, mediaId: String): MediaReadGrant

    suspend fun delete(expectedUserId: String, kind: MediaKind, mediaId: String): MediaDeleteResponse

    /** The bytes behind a signed read URL, at most [maxBytes]. */
    suspend fun download(readUrl: String, maxBytes: Long = MediaLimits.MAX_BYTES): ByteArray
}

class MediaApiImpl internal constructor(
    private val client: HttpClient,
    baseUrl: String,
    auth: AuthSessionProvider,
    serverClock: ServerClock,
    requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) : MediaApi {
    private val root = baseUrl.trimEnd('/')
    private val calls = AuthorizedCalls(client, auth, serverClock, requestIdFactory)

    override suspend fun upload(
        expectedUserId: String,
        kind: MediaKind,
        mediaId: String,
        fields: Map<String, String>,
        file: File,
        mimeType: String,
    ): MediaUploadResponse {
        // Refused locally exactly like the server would (413, permanent): never sent.
        if (file.length() > MediaLimits.MAX_BYTES) throw tooLarge(HTTP_PAYLOAD_TOO_LARGE, MEDIA_TOO_LARGE)
        val bytes = file.readBytes()
        return calls.execute(
            expectedUserId,
            configure = {
                method = HttpMethod.Post
                url("$root/api/v1/media/${kind.pathSegment}")
                // Built per attempt: a 401 retry sends a fresh body.
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("id", mediaId)
                            fields.forEach { (name, value) -> append(name, value) }
                            append(
                                "file",
                                bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, mimeType)
                                    append(HttpHeaders.ContentDisposition, "filename=\"$mediaId.jpg\"")
                                },
                            )
                        },
                    ),
                )
            },
            decode = { ResponseJson.decodeFromString(MediaUploadResponse.serializer(), it) },
        )
    }

    override suspend fun readGrant(expectedUserId: String, mediaId: String): MediaReadGrant = calls.execute(
        expectedUserId,
        configure = {
            method = HttpMethod.Get
            url("$root/api/v1/media/read/${encodeId(mediaId)}")
        },
        decode = { ResponseJson.decodeFromString(MediaReadGrant.serializer(), it) },
    )

    override suspend fun delete(expectedUserId: String, kind: MediaKind, mediaId: String): MediaDeleteResponse = calls.execute(
        expectedUserId,
        configure = {
            method = HttpMethod.Delete
            url("$root/api/v1/media/${kind.pathSegment}/${encodeId(mediaId)}")
        },
        decode = { ResponseJson.decodeFromString(MediaDeleteResponse.serializer(), it) },
    )

    override suspend fun download(readUrl: String, maxBytes: Long): ByteArray = try {
        client.prepareGet { url(readUrl) }.execute { response ->
            val status = response.status.value
            if (status !in 200..299) throw signedUrlFailure(status)
            val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > maxBytes) throw tooLarge(status)
            val channel = response.bodyAsChannel()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                if (out.size() > maxBytes) throw tooLarge(status)
            }
            out.toByteArray()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: SyncApiException) {
        throw e
    } catch (e: Exception) {
        throw SyncApiException.Transport(e)
    }

    private fun encodeId(id: String): String = id.encodeURLPathPart()

    /** A signed URL refused or expired (403), or the store failing: never a session problem. */
    private fun signedUrlFailure(status: Int) = SyncApiException.Api(
        httpStatus = status,
        code = ApiErrorCode.Unknown,
        rawCode = SIGNED_URL_REJECTED,
        retryable = status == 429 || status >= 500,
        retryAfterSeconds = null,
        mutationId = null,
        details = null,
        message = "Signed read URL failed",
    )

    private fun tooLarge(status: Int, rawCode: String = DOWNLOAD_TOO_LARGE) = SyncApiException.Api(
        httpStatus = status,
        code = ApiErrorCode.fromWire(rawCode),
        rawCode = rawCode,
        retryable = false,
        retryAfterSeconds = null,
        mutationId = null,
        details = null,
        message = "Downloaded media exceeds the limit",
    )

    companion object {
        const val SIGNED_URL_REJECTED = "SIGNED_URL_REJECTED"
        const val DOWNLOAD_TOO_LARGE = "DOWNLOAD_TOO_LARGE"
        const val MEDIA_TOO_LARGE = "MEDIA_TOO_LARGE"
        private const val HTTP_PAYLOAD_TOO_LARGE = 413
        private const val BUFFER_BYTES = 64 * 1024
    }
}

/** Production factory (OkHttp engine); share the app's [serverClock]. */
fun createMediaApi(baseUrl: String, auth: AuthSessionProvider, serverClock: ServerClock): MediaApi =
    MediaApiImpl(createGymLogHttpClient(), baseUrl, auth, serverClock)
