package com.chrisgoac.gymlog.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.sync.SyncLocks
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.database.entity.ExercisePhotoEntity
import com.chrisgoac.gymlog.database.entity.PendingMediaState
import com.chrisgoac.gymlog.database.entity.ProgressPhotoEntity
import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.model.media.EnqueueResult
import com.chrisgoac.gymlog.model.media.InvalidImage
import com.chrisgoac.gymlog.model.media.MediaDeleteResponse
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaKind
import com.chrisgoac.gymlog.model.media.MediaLimits
import com.chrisgoac.gymlog.model.media.MediaOwner
import com.chrisgoac.gymlog.model.media.MediaReadGrant
import com.chrisgoac.gymlog.model.media.MediaStatus
import com.chrisgoac.gymlog.model.media.MediaUploadResponse
import com.chrisgoac.gymlog.model.media.MediaWorkOutcome
import com.chrisgoac.gymlog.network.MediaApi
import com.chrisgoac.gymlog.network.MediaApiImpl
import com.chrisgoac.gymlog.network.SyncApiException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val opened = mutableListOf<GymLogDatabase>()
    private var now = 1_800_000_000_000L
    private var ids = 0
    private val filesRoot = File(context.filesDir, "media-test").apply { deleteRecursively() }
    private val cacheRoot = File(context.cacheDir, "media-test").apply { deleteRecursively() }
    private val sources = File(context.cacheDir, "sources").apply { deleteRecursively(); mkdirs() }

    private class SwitchableManager(initial: OpenUserDatabase) : UserDatabaseManager {
        override val current = MutableStateFlow<OpenUserDatabase?>(initial)
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    /** A fake media server: stores one object per id (replays return it), signs URLs that expire. */
    private inner class FakeMediaApi : MediaApi {
        val objects = HashMap<String, String>() // mediaId -> objectKey
        val uploads = mutableListOf<Pair<String, Map<String, String>>>()
        val deletes = mutableListOf<String>()
        var grantsIssued = 0
        var uploadFailure: (() -> Exception)? = null
        var loseUploadResponse = false
        var deleteFailure: (() -> Exception)? = null
        var grantFailure: (() -> Exception)? = null
        var downloadFailure: (() -> Exception?)? = null
        val downloads = mutableListOf<String>()

        override suspend fun upload(expectedUserId: String, kind: MediaKind, mediaId: String, fields: Map<String, String>, file: File, mimeType: String): MediaUploadResponse {
            uploadFailure?.let { throw it() }
            uploads += mediaId to fields
            val key = objects.getOrPut(mediaId) { "users/h/${kind.wire}/obj-${objects.size + 1}" }
            if (loseUploadResponse) {
                loseUploadResponse = false
                throw SyncApiException.Transport(IOException("connection reset"))
            }
            return MediaUploadResponse(mediaId, key, "https://r2.test/$key?sig=${++grantsIssued}", now + 300_000)
        }

        override suspend fun readGrant(expectedUserId: String, mediaId: String): MediaReadGrant {
            grantFailure?.let { throw it() }
            grantsIssued++
            return MediaReadGrant(mediaId, "https://r2.test/$mediaId?sig=$grantsIssued", now + 300_000)
        }

        override suspend fun delete(expectedUserId: String, kind: MediaKind, mediaId: String): MediaDeleteResponse {
            deleteFailure?.let { throw it() }
            deletes += mediaId
            if (objects.remove(mediaId) == null) throw apiError(404, "MEDIA_NOT_FOUND", false)
            return MediaDeleteResponse(mediaId, true)
        }

        override suspend fun download(readUrl: String, maxBytes: Long): ByteArray {
            downloadFailure?.invoke()?.let { throw it }
            downloads += readUrl
            return jpegBytes(8, 8)
        }
    }

    private fun apiError(status: Int, code: String, retryable: Boolean) = SyncApiException.Api(
        httpStatus = status, code = ApiErrorCode.fromWire(code), rawCode = code, retryable = retryable,
        retryAfterSeconds = null, mutationId = null, details = null, message = code,
    )

    private fun memoryDb() = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).allowMainThreadQueries().build().also { opened += it }

    private val dbA = memoryDb()
    private val manager = SwitchableManager(OpenUserDatabase(USER_A, dbA))
    private val api = FakeMediaApi()
    private val scheduled = mutableListOf<Pair<String, String>>()
    private val locks = SyncLocks()

    private fun repo(maxSourceBytes: Long = MediaLimits.MAX_SOURCE_BYTES) = OfflineMediaRepository(
        databases = manager,
        api = api,
        filesRoot = filesRoot,
        cacheRoot = cacheRoot,
        openSource = { uri -> File(uri.removePrefix("file://")).takeIf { it.isFile }?.let(::FileInputStream) },
        schedule = { hash, id -> scheduled += hash to id },
        locks = locks,
        now = { now },
        newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
        maxSourceBytes = maxSourceBytes,
    )

    private val media = repo()

    @After fun tearDown() = opened.forEach { if (it.isOpen) it.close() }

    private val hashA = UserDatabaseManagerImpl.userHash(USER_A)
    private fun pendingDir(hash: String = hashA) = File(filesRoot, "users/$hash/media/pending")
    private fun cacheDir(hash: String = hashA) = File(cacheRoot, "users/$hash/media")

    // ── Fixtures ──

    private fun jpegBytes(width: Int, height: Int, quality: Int = 90): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Left half red, right half blue: tells where the image's left edge ended up.
        for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, if (x < width / 2) Color.RED else Color.BLUE)
        return java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }

    private fun sourceJpeg(width: Int, height: Int, orientation: Int? = null): String {
        val file = File(sources, "src-${++ids}.jpg")
        file.writeBytes(jpegBytes(width, height))
        if (orientation != null) {
            ExifInterface(file.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,25/1,0/1")
                saveAttributes()
            }
        }
        return "file://${file.path}"
    }

    private fun sourceBytes(name: String, bytes: ByteArray): String =
        "file://" + File(sources, name).apply { writeBytes(bytes) }.path

    /** A valid 2×1 24-bit BMP: a real image in a format the app refuses. */
    private fun bmp(): ByteArray {
        val rowBytes = 8 // 2 px * 3 bytes, padded to 4
        val size = 54 + rowBytes
        val b = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put('B'.code.toByte()).put('M'.code.toByte()).putInt(size).putInt(0).putInt(54)
        b.putInt(40).putInt(2).putInt(1).putShort(1).putShort(24).putInt(0).putInt(rowBytes).putInt(2835).putInt(2835).putInt(0).putInt(0)
        b.put(byteArrayOf(0, 0, -1, -1, 0, 0, 0, 0))
        return b.array()
    }

    private fun queuedId(result: EnqueueResult) = (result as EnqueueResult.Queued).mediaId

    private fun pendingFile(id: String) = File(pendingDir(), "$id.jpg")

    // ── Compression ──

    @Test fun `EXIF rotation is applied to the pixels and the output carries no EXIF`() = runTest {
        val id = queuedId(media.enqueue(sourceJpeg(40, 20, ExifInterface.ORIENTATION_ROTATE_90), MediaOwner.Exercise("e1")))
        val out = BitmapFactory.decodeFile(pendingFile(id).path)

        assertEquals(20, out.width)
        assertEquals(40, out.height)
        // Rotating 90° clockwise puts the source's left (red) half at the top.
        assertEquals(Color.RED, nearest(out.getPixel(10, 5)))
        assertEquals(Color.BLUE, nearest(out.getPixel(10, 35)))
        val exif = ExifInterface(pendingFile(id).path)
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
    }

    @Test fun `mirrored orientations flip the image`() = runTest {
        val id = queuedId(media.enqueue(sourceJpeg(40, 20, ExifInterface.ORIENTATION_FLIP_HORIZONTAL), MediaOwner.Exercise("e1")))
        val out = BitmapFactory.decodeFile(pendingFile(id).path)
        assertEquals(40, out.width)
        assertEquals(Color.BLUE, nearest(out.getPixel(5, 10)))
        assertEquals(Color.RED, nearest(out.getPixel(35, 10)))
    }

    @Test fun `large photos are bounded to the max side and size, small ones are not upscaled`() = runTest {
        val big = queuedId(media.enqueue(sourceJpeg(4000, 3000), MediaOwner.Exercise("e1")))
        val bigOut = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(pendingFile(big).path, it) }
        assertEquals(MediaLimits.COMPRESSED_MAX_SIDE, bigOut.outWidth)
        assertEquals(1536, bigOut.outHeight)
        assertEquals("image/jpeg", bigOut.outMimeType)
        assertTrue(pendingFile(big).length() <= MediaLimits.MAX_BYTES)

        val small = queuedId(media.enqueue(sourceJpeg(120, 60), MediaOwner.Exercise("e1")))
        val smallOut = BitmapFactory.decodeFile(pendingFile(small).path)
        assertEquals(120, smallOut.width)
        assertEquals(60, smallOut.height)
    }

    @Test fun `corrupt, unsupported, oversized and unreadable sources are refused and leave nothing behind`() = runTest {
        assertEquals(EnqueueResult.Invalid(InvalidImage.CORRUPT), media.enqueue(sourceBytes("junk.jpg", ByteArray(500) { it.toByte() }), MediaOwner.Exercise("e1")))
        assertEquals(EnqueueResult.Invalid(InvalidImage.CORRUPT), media.enqueue(sourceBytes("cut.jpg", jpegBytes(64, 64).copyOf(40)), MediaOwner.Exercise("e1")))
        assertEquals(EnqueueResult.Invalid(InvalidImage.UNSUPPORTED), media.enqueue(sourceBytes("x.bmp", bmp()), MediaOwner.Exercise("e1")))
        assertEquals(EnqueueResult.Invalid(InvalidImage.TOO_LARGE), repo(maxSourceBytes = 100).enqueue(sourceJpeg(64, 64), MediaOwner.Exercise("e1")))
        assertEquals(EnqueueResult.Invalid(InvalidImage.UNREADABLE), media.enqueue("file:///missing.jpg", MediaOwner.Exercise("e1")))

        assertTrue(dbA.pendingMediaDao().getAll().isEmpty())
        assertTrue(pendingDir().listFiles().orEmpty().isEmpty())
        assertTrue(scheduled.isEmpty())
    }

    // ── Queue ──

    @Test fun `offline enqueue is fully local, scheduled per account, and transient failures retry`() = runTest {
        api.uploadFailure = { SyncApiException.Transport(IOException("offline")) }
        val id = queuedId(media.enqueue(sourceJpeg(100, 100), MediaOwner.Progress(fecha = 5, angulo = "lado", nota = null)))

        assertEquals(listOf(hashA to id), scheduled)
        assertTrue(pendingFile(id).isFile)
        assertTrue(api.uploads.isEmpty())
        val item = media.observe(MediaKind.PROGRESS).first().single()
        assertEquals(MediaStatus.PendingUpload(0, null), item.status)
        assertNull(item.objectKey)

        assertEquals(MediaWorkOutcome.RETRY, media.process(USER_A, id))
        assertEquals(MediaStatus.PendingUpload(1, "NETWORK"), media.observe(MediaKind.PROGRESS).first().single().status)
        api.uploadFailure = { apiError(503, "MEDIA_STORAGE_UNAVAILABLE", true) }
        assertEquals(MediaWorkOutcome.RETRY, media.process(USER_A, id))
        api.uploadFailure = { SyncApiException.SessionRequired() }
        assertEquals(MediaWorkOutcome.SESSION_REQUIRED, media.process(USER_A, id))
        assertTrue(pendingFile(id).isFile)
    }

    @Test fun `after process death a new instance finds the queued photo and uploads it once`() = runTest {
        val id = queuedId(media.enqueue(sourceJpeg(100, 100), MediaOwner.Exercise("e1")))

        val restarted = repo()
        assertEquals(listOf(id), restarted.pendingWork(USER_A))
        assertEquals(MediaWorkOutcome.DONE, restarted.process(USER_A, id))

        assertEquals(listOf(id to mapOf("exerciseId" to "e1")), api.uploads)
        val row = dbA.exercisePhotoDao().findById(id)!!
        assertEquals(api.objects[id], row.key)
        assertEquals("/api/media/read/$id", row.url)
        assertNull(dbA.pendingMediaDao().findById(id))
        assertTrue(restarted.pendingWork(USER_A).isEmpty())

        // Pull later delivers the server row: same id, still one photo.
        dbA.exercisePhotoDao().upsert(row.copy(updatedAt = now + 5, serverVersion = 42))
        val items = media.observe(MediaKind.EXERCISE).first()
        assertEquals(1, items.size)
        assertEquals(MediaStatus.Synced, items.single().status)
    }

    @Test fun `an ingest interrupted before its row leaves an orphan file that the next resume removes`() = runTest {
        val orphan = File(pendingDir().apply { mkdirs() }, "orphan.jpg").apply { writeBytes(byteArrayOf(1)); setLastModified(0) }
        media.pendingWork(USER_A)
        assertFalse(orphan.exists())
    }

    @Test fun `a lost upload response and duplicate workers replay the same id and store one object`() = runTest {
        val id = queuedId(media.enqueue(sourceJpeg(100, 100), MediaOwner.Exercise("e1")))
        api.loseUploadResponse = true
        assertEquals(MediaWorkOutcome.RETRY, media.process(USER_A, id))

        val outcomes = listOf(async { media.process(USER_A, id) }, async { repo().process(USER_A, id) }).awaitAll()

        assertEquals(listOf(MediaWorkOutcome.DONE, MediaWorkOutcome.DONE), outcomes)
        assertEquals(listOf(id, id), api.uploads.map { it.first }) // the lost attempt + one replay
        assertEquals(1, api.objects.size)
        assertEquals(1, dbA.exercisePhotoDao().getAll().size)
    }

    @Test fun `a permanent rejection keeps the file for retry or discard`() = runTest {
        val id = queuedId(media.enqueue(sourceJpeg(100, 100), MediaOwner.Exercise("e1")))
        api.uploadFailure = { apiError(400, "INVALID_REFERENCE", false) }

        assertEquals(MediaWorkOutcome.DONE, media.process(USER_A, id))
        assertEquals(PendingMediaState.REJECTED, dbA.pendingMediaDao().findById(id)!!.state)
        assertEquals(MediaStatus.Rejected("INVALID_REFERENCE"), media.observe(MediaKind.EXERCISE).first().single().status)
        assertTrue(pendingFile(id).isFile)
        assertEquals(MediaImage.Available(pendingFile(id).path), media.image(id))
        // A rejected photo is not picked up again by a resume.
        assertTrue(media.pendingWork(USER_A).isEmpty())

        scheduled.clear()
        media.retry(id)
        assertEquals(listOf(hashA to id), scheduled)
        api.uploadFailure = null
        assertEquals(MediaWorkOutcome.DONE, media.process(USER_A, id))
        assertEquals(1, dbA.exercisePhotoDao().getAll().size)

        val other = queuedId(media.enqueue(sourceJpeg(100, 100), MediaOwner.Exercise("e1")))
        api.uploadFailure = { apiError(413, "MEDIA_TOO_LARGE", false) }
        media.process(USER_A, other)
        media.discard(other)
        assertNull(dbA.pendingMediaDao().findById(other))
        assertFalse(pendingFile(other).exists())
    }

    @Test fun `a confirmed upload moves the original out of the pending directory into the private cache`() = runTest {
        val id = queuedId(media.enqueue(sourceJpeg(100, 100), MediaOwner.Exercise("e1")))
        media.process(USER_A, id)

        assertTrue(pendingDir().listFiles().orEmpty().isEmpty())
        val cached = File(cacheDir(), "$id.jpg")
        assertTrue(cached.isFile)
        assertEquals(MediaImage.Available(cached.path), media.image(id))
        assertEquals(0, api.downloads.size)
    }

    @Test fun `deleting a synced photo hides it at once and tombstones it once the server confirms`() = runTest {
        dbA.progressPhotoDao().upsert(ProgressPhotoEntity("p1", 10, null, "/api/media/read/p1", "users/h/progress/k", 7, "frente", null, 3))
        api.objects["p1"] = "users/h/progress/k"
        val cached = File(cacheDir().apply { mkdirs() }, "p1.jpg").apply { writeBytes(byteArrayOf(1)) }
        api.deleteFailure = { SyncApiException.Transport(IOException("offline")) }

        media.delete("p1")
        assertTrue(media.observe(MediaKind.PROGRESS).first().isEmpty())
        assertFalse(cached.exists())
        assertEquals(listOf(hashA to "p1"), scheduled)
        assertEquals(MediaWorkOutcome.RETRY, media.process(USER_A, "p1"))
        assertTrue(media.observe(MediaKind.PROGRESS).first().isEmpty())

        api.deleteFailure = null
        assertEquals(MediaWorkOutcome.DONE, media.process(USER_A, "p1"))
        assertEquals(listOf("p1"), api.deletes)
        assertTrue(dbA.progressPhotoDao().findById("p1")!!.deletedAt != null)
        assertNull(dbA.pendingMediaDao().findById("p1"))
    }

    @Test fun `deleting a photo still queued deletes it remotely too, since its upload may have landed`() = runTest {
        val id = queuedId(media.enqueue(sourceJpeg(100, 100), MediaOwner.Exercise("e1")))
        api.loseUploadResponse = true
        media.process(USER_A, id) // stored server side, response lost

        media.delete(id)
        assertFalse(pendingFile(id).exists())
        assertTrue(media.observe(MediaKind.EXERCISE).first().isEmpty())
        assertEquals(MediaWorkOutcome.DONE, media.process(USER_A, id))
        assertEquals(listOf(id), api.deletes)
        assertTrue(api.objects.isEmpty())
        assertTrue(dbA.exercisePhotoDao().getAll().isEmpty())

        // Never reached the server: the 404 is a confirmed delete.
        val never = queuedId(media.enqueue(sourceJpeg(100, 100), MediaOwner.Exercise("e1")))
        media.delete(never)
        assertEquals(MediaWorkOutcome.DONE, media.process(USER_A, never))
        assertNull(dbA.pendingMediaDao().findById(never))
    }

    // ── Read grants and cache ──

    @Test fun `signed URLs live in memory only and are refreshed once expired`() = runTest {
        dbA.exercisePhotoDao().upsert(ExercisePhotoEntity("x1", 10, null, "e1", "/api/media/read/x1", "users/h/exercise/k", 1))
        val cached = File(cacheDir(), "x1.jpg")

        assertEquals(MediaImage.Available(cached.path), media.image("x1"))
        assertEquals(1, api.grantsIssued)

        cached.delete() // the OS evicted the cache: the in-memory grant is still valid
        media.image("x1")
        assertEquals(1, api.grantsIssued)

        cached.delete()
        now += 300_000 // grant expired
        media.image("x1")
        assertEquals(2, api.grantsIssued)

        // A URL the store refuses (expired early) is replaced by one fresh grant.
        cached.delete()
        var refusals = 1
        api.downloadFailure = { if (refusals-- > 0) apiError(403, MediaApiImpl.SIGNED_URL_REJECTED, false) else null }
        assertEquals(MediaImage.Available(cached.path), media.image("x1"))
        assertEquals(3, api.grantsIssued)

        // A store that keeps refusing is not retried in a loop.
        cached.delete()
        api.downloadFailure = { apiError(403, MediaApiImpl.SIGNED_URL_REJECTED, false) }
        assertEquals(MediaImage.Unavailable(MediaImage.Unavailable.Reason.OFFLINE), media.image("x1"))
        assertEquals(4, api.grantsIssued) // the held grant + one refresh

        // No URL is ever written to disk.
        val written = (cacheRoot.walkTopDown() + filesRoot.walkTopDown()).filter { it.isFile }.map { it.readBytes().decodeToString() }
        assertTrue(written.none { it.contains("https://r2.test") })
    }

    @Test fun `an uncached photo offline is unavailable, not an error`() = runTest {
        dbA.exercisePhotoDao().upsert(ExercisePhotoEntity("x1", 10, null, "e1", "/api/media/read/x1", "k", 1))
        api.grantFailure = { SyncApiException.Transport(IOException("offline")) }
        assertEquals(MediaImage.Unavailable(MediaImage.Unavailable.Reason.OFFLINE), media.image("x1"))
        api.grantFailure = { apiError(404, "MEDIA_NOT_FOUND", false) }
        assertEquals(MediaImage.Unavailable(MediaImage.Unavailable.Reason.NOT_FOUND), media.image("x1"))
    }

    @Test fun `files, caches and work stay inside the exact account`() = runTest {
        val id = queuedId(media.enqueue(sourceJpeg(100, 100), MediaOwner.Exercise("e1")))
        media.process(USER_A, id)
        val aCopy = File(cacheDir(), "$id.jpg")
        assertTrue(aCopy.isFile)

        val dbB = memoryDb()
        manager.current.value = OpenUserDatabase(USER_B, dbB)
        val hashB = UserDatabaseManagerImpl.userHash(USER_B)
        assertNotEquals(hashA, hashB)

        // B never sees A's file, even for the same id; B asks the server as B (and is refused).
        api.grantFailure = { apiError(404, "MEDIA_NOT_FOUND", false) }
        assertEquals(MediaImage.Unavailable(MediaImage.Unavailable.Reason.NOT_FOUND), media.image(id))
        assertTrue(media.observe(MediaKind.EXERCISE).first().isEmpty())

        // Work of A while B is open is obsolete and touches nothing.
        val queued = queuedId(media.enqueue(sourceJpeg(100, 100), MediaOwner.Exercise("e2")))
        assertTrue(File(filesRoot, "users/$hashB/media/pending/$queued.jpg").isFile)
        assertEquals(MediaWorkOutcome.UNAVAILABLE, media.process(USER_A, queued))
        assertTrue(media.pendingWork(USER_A).isEmpty())
        assertEquals(hashB to queued, scheduled.last())
        assertTrue(aCopy.isFile)
    }

    private fun nearest(pixel: Int): Int = if (Color.red(pixel) > Color.blue(pixel)) Color.RED else Color.BLUE

    private companion object {
        const val USER_A = "user_a"
        const val USER_B = "user_b"
    }
}
