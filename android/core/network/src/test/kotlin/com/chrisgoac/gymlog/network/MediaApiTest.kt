package com.chrisgoac.gymlog.network

import com.chrisgoac.gymlog.model.media.MediaKind
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
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MediaApiTest {
    private class FakeAuth(private val owner: String = USER, private val refreshed: String = "tok-2") : AuthSessionProvider {
        override val state = MutableStateFlow<AuthState>(AuthState.SignedIn(owner))
        override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String? =
            if (expectedUserId != owner) null else if (forceRefresh) refreshed else "tok-1"
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(
        requests: MutableList<HttpRequestData>,
        bodies: MutableList<ByteArray> = mutableListOf(),
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = MediaApiImpl(
        createGymLogHttpClient(
            MockEngine { request ->
                requests += request
                bodies += request.body.toByteArray()
                handler(request)
            },
        ),
        "https://example.test/",
        FakeAuth(),
        ServerClock(),
        requestIdFactory = { "req-1" },
    )

    private fun jpeg(): File = Files.createTempFile("media", ".jpg").toFile().apply {
        writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))
        deleteOnExit()
    }

    @Test
    fun uploadSendsMultipartWithIdFieldsAndFileUnderBearer() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<ByteArray>()
        val api = api(requests, bodies) {
            respond(
                """{"mediaId":"m1","objectKey":"users/h/progress/u","readUrl":"https://r2.test/x?sig=1","readUrlExpiresAt":5}""",
                HttpStatusCode.Created, json,
            )
        }
        val response = api.upload(USER, MediaKind.PROGRESS, "m1", mapOf("fecha" to "10", "angulo" to "lado"), jpeg())

        assertEquals("users/h/progress/u", response.objectKey)
        val sent = requests.single()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("https://example.test/api/v1/media/progress", sent.url.toString())
        assertEquals("Bearer tok-1", sent.headers[HttpHeaders.Authorization])
        assertTrue(sent.body.contentType.toString().startsWith("multipart/form-data"))
        val body = bodies.single().decodeToString()
        listOf("name=\"id\"", "m1", "name=\"fecha\"", "10", "name=\"angulo\"", "lado", "name=\"file\"; filename=\"m1.jpg\"", "image/jpeg")
            .forEach { assertTrue("missing $it", body.contains(it)) }
    }

    @Test
    fun uploadRetriesA401OnceWithARefreshedTokenAndAFreshBody() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val bodies = mutableListOf<ByteArray>()
        val api = api(requests, bodies) {
            if (requests.size == 1) {
                respond("", HttpStatusCode.Unauthorized)
            } else {
                respond("""{"mediaId":"m1","objectKey":"k","readUrl":"https://r2.test/x","readUrlExpiresAt":5}""", HttpStatusCode.Created, json)
            }
        }
        api.upload(USER, MediaKind.EXERCISE, "m1", mapOf("exerciseId" to "e1"), jpeg())
        assertEquals(listOf("Bearer tok-1", "Bearer tok-2"), requests.map { it.headers[HttpHeaders.Authorization] })
        assertTrue(bodies[1].decodeToString().contains("name=\"exerciseId\""))
    }

    @Test
    fun anotherAccountsCallSendsNothing() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) { error("must not be called") }
        try {
            api.readGrant("someone-else", "m1")
            fail()
        } catch (_: SyncApiException.SessionRequired) {
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun conflictsMapToNonRetryableApiErrors() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) {
            respond("""{"error":{"code":"MEDIA_DELETED","message":"m","retryable":false}}""", HttpStatusCode.Conflict, json)
        }
        try {
            api.upload(USER, MediaKind.EXERCISE, "m1", mapOf("exerciseId" to "e1"), jpeg())
            fail()
        } catch (e: SyncApiException.Api) {
            assertEquals(409, e.httpStatus)
            assertEquals("MEDIA_DELETED", e.rawCode)
            assertFalse(e.retryable)
        }
    }

    @Test
    fun readGrantAndDeleteUseTheV1Routes() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) { request ->
            if (request.method == HttpMethod.Delete) {
                respond("""{"mediaId":"m 1","deleted":true}""", HttpStatusCode.OK, json)
            } else {
                respond("""{"mediaId":"m 1","readUrl":"https://r2.test/a","expiresAt":99}""", HttpStatusCode.OK, json)
            }
        }
        assertEquals(99, api.readGrant(USER, "m 1").expiresAt)
        assertTrue(api.delete(USER, MediaKind.PROGRESS, "m 1").deleted)
        assertEquals("https://example.test/api/v1/media/read/m%201", requests[0].url.toString())
        assertEquals("https://example.test/api/v1/media/progress/m%201", requests[1].url.toString())
    }

    @Test
    fun downloadNeverSendsTheBearerToken() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) { respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK) }
        assertArrayEquals(byteArrayOf(1, 2, 3), api.download("https://r2.test/obj?X-Amz-Signature=s"))
        assertNull(requests.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun downloadRejectsOversizedBodiesAndExpiredUrls() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = api(requests) { request ->
            if (request.url.encodedPath.endsWith("big")) respond(ByteArray(11), HttpStatusCode.OK) else respond("", HttpStatusCode.Forbidden)
        }
        try {
            api.download("https://r2.test/big", maxBytes = 10)
            fail()
        } catch (e: SyncApiException.Api) {
            assertEquals(MediaApiImpl.DOWNLOAD_TOO_LARGE, e.rawCode)
        }
        try {
            api.download("https://r2.test/expired")
            fail()
        } catch (e: SyncApiException.Api) {
            assertEquals(403, e.httpStatus)
            assertEquals(MediaApiImpl.SIGNED_URL_REJECTED, e.rawCode)
        }
    }

    private companion object {
        const val USER = "user_1"
    }
}
