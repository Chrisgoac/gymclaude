package com.chrisgoac.gymlog.data.media

import androidx.room.withTransaction
import com.chrisgoac.gymlog.data.sync.SyncLocks
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.database.entity.ExercisePhotoEntity
import com.chrisgoac.gymlog.database.entity.PendingMediaEntity
import com.chrisgoac.gymlog.database.entity.PendingMediaOperation
import com.chrisgoac.gymlog.database.entity.PendingMediaState
import com.chrisgoac.gymlog.database.entity.ProgressPhotoEntity
import com.chrisgoac.gymlog.domain.media.MediaRepository
import com.chrisgoac.gymlog.model.media.EnqueueResult
import com.chrisgoac.gymlog.model.media.InvalidImage
import com.chrisgoac.gymlog.model.media.MediaImage
import com.chrisgoac.gymlog.model.media.MediaItem
import com.chrisgoac.gymlog.model.media.MediaKind
import com.chrisgoac.gymlog.model.media.MediaLimits
import com.chrisgoac.gymlog.model.media.MediaOwner
import com.chrisgoac.gymlog.model.media.MediaReadGrant
import com.chrisgoac.gymlog.model.media.MediaStatus
import com.chrisgoac.gymlog.model.media.MediaWorkOutcome
import com.chrisgoac.gymlog.network.MediaApi
import com.chrisgoac.gymlog.network.MediaApiImpl
import com.chrisgoac.gymlog.network.SyncApiException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [MediaRepository] over the open account's Room database, its private directories and the v1 media
 * endpoints.
 *
 * - Pending originals (the compressed JPEG awaiting upload) live in `files/users/<hash>/media/pending`;
 *   displayable copies in `cache/users/<hash>/media`. Both are under the account's own directories,
 *   which [com.chrisgoac.gymlog.domain.LocalAccountCleaner] removes on sign-out/account deletion.
 * - The queue is the local-only `pending_media` table, never the outbox: the media service creates and
 *   tombstones the server rows itself. A confirmed upload writes the row locally only if pull has not
 *   brought it yet (same id, so pull later replaces it; never a duplicate) and moves the file to the
 *   cache; a confirmed delete tombstones the local row.
 * - Signed read URLs are kept in memory only, keyed by account and photo, and refreshed when expired.
 */
class OfflineMediaRepository(
    private val databases: UserDatabaseManager,
    private val api: MediaApi,
    private val filesRoot: File,
    private val cacheRoot: File,
    /** Opens a picked/captured content URI; null when it cannot be opened. */
    private val openSource: (String) -> InputStream?,
    /** Queues the background upload/delete of one photo (unique per account + photo). */
    private val schedule: suspend (userHash: String, mediaId: String) -> Unit,
    /** Like [schedule] for an explicit user retry: starts now instead of waiting behind backoff. */
    private val scheduleNow: suspend (userHash: String, mediaId: String) -> Unit = schedule,
    private val locks: SyncLocks,
    /** Server-corrected clock (grants carry server epoch expiries). */
    private val now: () -> Long,
    private val compressor: ImageCompressor = ImageCompressor(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val maxSourceBytes: Long = MediaLimits.MAX_SOURCE_BYTES,
    /** Display-cache budget per account; least recently used copies are evicted beyond it. */
    private val maxCacheBytes: Long = DEFAULT_CACHE_BYTES,
) : MediaRepository {
    private val grants = ConcurrentHashMap<String, MediaReadGrant>()

    // ── Ingest ──

    override suspend fun enqueue(sourceUri: String, owner: MediaOwner): EnqueueResult {
        validate(owner)
        val open = databases.current.value ?: error("No open account")
        val hash = UserDatabaseManagerImpl.userHash(open.userId)
        val id = newId()
        val dir = pendingDir(hash)
        val target = File(dir, "$id$JPEG")
        // Under the account lock (local-data deletion takes it too) and only while the account is still
        // the open one: a signed-out account's directory is never recreated.
        locks.forUser(open.userId).withLock {
            if (databases.current.value?.userId != open.userId) error("The account is no longer open")
            val failure = withContext(Dispatchers.IO) { ingest(sourceUri, dir, id, target) }
            if (failure != null) return EnqueueResult.Invalid(failure)
            insertPending(open, id, owner, target)
        }
        schedule(hash, id)
        return EnqueueResult.Queued(id)
    }

    private suspend fun insertPending(open: OpenUserDatabase, id: String, owner: MediaOwner, target: File) {
        open.database.pendingMediaDao().insert(
            PendingMediaEntity(
                id = id,
                kind = owner.kind.wire,
                operation = PendingMediaOperation.UPLOAD,
                state = PendingMediaState.PENDING,
                exerciseId = (owner as? MediaOwner.Exercise)?.exerciseId,
                fecha = (owner as? MediaOwner.Progress)?.fecha,
                angulo = (owner as? MediaOwner.Progress)?.angulo,
                nota = (owner as? MediaOwner.Progress)?.nota,
                fileName = target.name,
                attempts = 0,
                lastError = null,
                createdAt = now(),
            ),
        )
    }

    /** Copies then compresses into the private pending directory; null on success. */
    private fun ingest(sourceUri: String, dir: File, id: String, target: File): InvalidImage? {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the pending media directory")
        val copy = File(dir, "$id$SOURCE")
        try {
            val input = try {
                openSource(sourceUri)
            } catch (_: SecurityException) {
                null
            } catch (_: IOException) {
                null
            } ?: return InvalidImage.UNREADABLE
            val copied = try {
                input.use { copyCapped(it, copy, maxSourceBytes) }
            } catch (_: IOException) {
                return InvalidImage.UNREADABLE
            }
            if (!copied) return InvalidImage.TOO_LARGE
            return when (val result = compressor.compress(copy, target)) {
                is ImageCompressor.Result.Compressed -> null
                is ImageCompressor.Result.Failed -> result.reason.also { target.delete() }
            }
        } finally {
            copy.delete()
        }
    }

    private fun copyCapped(input: InputStream, target: File, maxBytes: Long): Boolean {
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(COPY_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) return false
                out.write(buffer, 0, read)
            }
        }
        return true
    }

    private fun validate(owner: MediaOwner) {
        when (owner) {
            is MediaOwner.Exercise -> require(owner.exerciseId.isNotBlank()) { "exerciseId is required" }
            is MediaOwner.Progress -> {
                require(owner.angulo in MediaLimits.PROGRESS_ANGLES) { "Unknown angle ${owner.angulo}" }
                require((owner.nota?.length ?: 0) <= MAX_NOTE_CHARS) { "Note is too long" }
            }
        }
    }

    // ── Reads ──

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(kind: MediaKind): Flow<List<MediaItem>> = databases.current.flatMapLatest { open ->
        if (open == null) return@flatMapLatest flowOf(emptyList())
        val db = open.database
        val synced: Flow<List<MediaItem>> = when (kind) {
            MediaKind.EXERCISE -> db.exercisePhotoDao().observeLive().mapList { row ->
                MediaItem(row.id, MediaOwner.Exercise(row.exerciseId), MediaStatus.Synced, row.key)
            }
            MediaKind.PROGRESS -> db.progressPhotoDao().observeLive().mapList { row ->
                MediaItem(row.id, MediaOwner.Progress(row.fecha, row.angulo, row.nota), MediaStatus.Synced, row.key)
            }
        }
        combine(synced, db.pendingMediaDao().observeByKind(kind.wire)) { rows, pending ->
            val deleting = pending.filter { it.operation == PendingMediaOperation.DELETE }.mapTo(HashSet()) { it.id }
            val visible = rows.filter { it.id !in deleting }
            val known = visible.mapTo(HashSet()) { it.id }
            visible + pending
                .filter { it.operation == PendingMediaOperation.UPLOAD && it.id !in known }
                .mapNotNull { it.toItem() }
        }
    }

    override suspend fun image(mediaId: String): MediaImage {
        val open = databases.current.value ?: return MediaImage.Unavailable(MediaImage.Unavailable.Reason.SESSION_REQUIRED)
        val hash = UserDatabaseManagerImpl.userHash(open.userId)
        open.database.pendingMediaDao().findById(mediaId)?.let { row ->
            if (row.operation == PendingMediaOperation.DELETE) return MediaImage.Unavailable(MediaImage.Unavailable.Reason.NOT_FOUND)
            val file = row.fileName?.let { File(pendingDir(hash), it) }
            if (file != null && file.isFile) return MediaImage.Available(file.path)
        }
        val cached = cacheFile(hash, mediaId)
        if (cached.isFile) {
            cached.setLastModified(System.currentTimeMillis()) // LRU recency
            return MediaImage.Available(cached.path)
        }
        return download(open.userId, hash, mediaId, cached)
    }

    private suspend fun download(userId: String, hash: String, mediaId: String, target: File): MediaImage {
        repeat(2) { attempt ->
            val grant = try {
                validGrant(hash, mediaId) ?: api.readGrant(userId, mediaId).also { grants[grantKey(hash, mediaId)] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncApiException) {
                return unavailable(e)
            }
            val bytes = try {
                api.download(grant.readUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncApiException.Api) {
                // An expired/refused signed URL: forget it and ask for a fresh grant once.
                grants.remove(grantKey(hash, mediaId))
                if (e.rawCode == MediaApiImpl.SIGNED_URL_REJECTED && !e.retryable && attempt == 0) return@repeat
                return MediaImage.Unavailable(MediaImage.Unavailable.Reason.OFFLINE)
            } catch (e: SyncApiException) {
                return unavailable(e)
            }
            // The download ran unlocked; the write happens only if the account is still the open one
            // (local-data deletion holds this lock while it removes the directories).
            val stored = locks.forUser(userId).withLock {
                if (databases.current.value?.userId != userId) return@withLock false
                withContext(Dispatchers.IO) {
                    writeAtomically(target, bytes)
                    trimCache(hash)
                }
                true
            }
            if (!stored) return MediaImage.Unavailable(MediaImage.Unavailable.Reason.SESSION_REQUIRED)
            return MediaImage.Available(target.path)
        }
        return MediaImage.Unavailable(MediaImage.Unavailable.Reason.OFFLINE)
    }

    private fun unavailable(e: SyncApiException): MediaImage.Unavailable = MediaImage.Unavailable(
        when {
            e is SyncApiException.SessionRequired -> MediaImage.Unavailable.Reason.SESSION_REQUIRED
            e is SyncApiException.Api && e.httpStatus == HTTP_NOT_FOUND -> MediaImage.Unavailable.Reason.NOT_FOUND
            else -> MediaImage.Unavailable.Reason.OFFLINE
        },
    )

    /** A memory grant still usable for [GRANT_SAFETY_MILLIS]; expired ones are dropped. */
    private fun validGrant(hash: String, mediaId: String): MediaReadGrant? {
        val key = grantKey(hash, mediaId)
        val grant = grants[key] ?: return null
        if (grant.expiresAt - GRANT_SAFETY_MILLIS > now()) return grant
        grants.remove(key, grant)
        return null
    }

    // ── User actions ──

    override suspend fun delete(mediaId: String) {
        val open = databases.current.value ?: error("No open account")
        val db = open.database
        val hash = UserDatabaseManagerImpl.userHash(open.userId)
        var queued = false
        var dropFile: String? = null
        db.withTransaction {
            val dao = db.pendingMediaDao()
            val pending = dao.findById(mediaId)
            when {
                pending == null -> {
                    val row = db.exercisePhotoDao().findById(mediaId)?.takeIf { it.deletedAt == null }?.let {
                        newDelete(mediaId, MediaKind.EXERCISE, exerciseId = it.exerciseId)
                    } ?: db.progressPhotoDao().findById(mediaId)?.takeIf { it.deletedAt == null }?.let {
                        newDelete(mediaId, MediaKind.PROGRESS, fecha = it.fecha, angulo = it.angulo, nota = it.nota)
                    }
                    if (row != null) {
                        dao.insert(row)
                        queued = true
                    }
                }
                pending.operation == PendingMediaOperation.DELETE -> Unit
                // Refused for good: the server never stored it, so there is nothing to delete remotely.
                pending.state == PendingMediaState.REJECTED -> {
                    dao.delete(mediaId)
                    dropFile = pending.fileName
                }
                // The upload may have reached the server with its response lost: delete remotely too.
                else -> {
                    dao.upsert(pending.copy(operation = PendingMediaOperation.DELETE, fileName = null, attempts = 0, lastError = null))
                    dropFile = pending.fileName
                    queued = true
                }
            }
        }
        dropFile?.let { File(pendingDir(hash), it).delete() }
        cacheFile(hash, mediaId).delete()
        grants.remove(grantKey(hash, mediaId))
        if (queued) schedule(hash, mediaId)
    }

    override suspend fun retry(mediaId: String) {
        val open = databases.current.value ?: error("No open account")
        val dao = open.database.pendingMediaDao()
        // Read, check and write in one transaction under the account lock: a worker pass that finished
        // meanwhile (row gone, or turned into a delete) is never resurrected. A refused upload starts over
        // (attempts reset); one that merely keeps failing keeps its attempts and last error visible until
        // the new attempt finishes.
        val requeue = locks.forUser(open.userId).withLock {
            open.database.withTransaction {
                val row = dao.findById(mediaId)
                when {
                    row == null || row.operation != PendingMediaOperation.UPLOAD -> false
                    row.state == PendingMediaState.REJECTED -> {
                        dao.upsert(row.copy(state = PendingMediaState.PENDING, attempts = 0, lastError = null))
                        true
                    }
                    row.state == PendingMediaState.PENDING -> true
                    else -> false
                }
            }
        }
        if (requeue) scheduleNow(UserDatabaseManagerImpl.userHash(open.userId), mediaId)
    }

    override suspend fun discard(mediaId: String) {
        val open = databases.current.value ?: error("No open account")
        val dao = open.database.pendingMediaDao()
        val row = dao.findById(mediaId)?.takeIf { it.state == PendingMediaState.REJECTED } ?: return
        dao.delete(mediaId)
        row.fileName?.let { File(pendingDir(UserDatabaseManagerImpl.userHash(open.userId)), it).delete() }
    }

    // ── Background queue ──

    /**
     * Photos of [userId] still waiting for the server (to re-schedule after a process death or a new
     * sign-in). Also removes pending files no row refers to (an ingest interrupted by process death).
     */
    suspend fun pendingWork(userId: String): List<String> {
        val open = databases.current.value?.takeIf { it.userId == userId } ?: return emptyList()
        val rows = open.database.pendingMediaDao().getAll()
        val referenced = rows.mapNotNullTo(HashSet()) { it.fileName }
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - ORPHAN_AGE_MILLIS
            pendingDir(UserDatabaseManagerImpl.userHash(userId)).listFiles()
                ?.filter { it.name !in referenced && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
        sweep(userId)
        return open.database.pendingMediaDao().getAll().filter { it.state == PendingMediaState.PENDING }.map { it.id }
    }

    /**
     * Evicts what a remote tombstone (pull) made obsolete: cached copies whose photo is no longer live,
     * and queued uploads of photos the server already deleted (with their pending original). Run after
     * every sync and on resume.
     */
    suspend fun sweep(userId: String) = locks.forUser(userId).withLock {
        val open = databases.current.value?.takeIf { it.userId == userId } ?: return@withLock
        val db = open.database
        val hash = UserDatabaseManagerImpl.userHash(userId)
        val exercise = db.exercisePhotoDao().getAll()
        val progress = db.progressPhotoDao().getAll()
        val live = HashSet<String>()
        val tombstoned = HashSet<String>()
        exercise.forEach { if (it.deletedAt == null) live += it.id else tombstoned += it.id }
        progress.forEach { if (it.deletedAt == null) live += it.id else tombstoned += it.id }

        val dropped = ArrayList<PendingMediaEntity>()
        db.withTransaction {
            db.pendingMediaDao().getAll()
                .filter { it.operation == PendingMediaOperation.UPLOAD && it.id in tombstoned && it.id !in live }
                .forEach {
                    db.pendingMediaDao().delete(it.id)
                    dropped += it
                }
        }
        dropped.forEach { grants.remove(grantKey(hash, it.id)) }
        val keep = live.mapTo(HashSet()) { fileSafe(it) + JPEG }
        withContext(Dispatchers.IO) {
            dropped.forEach { row -> row.fileName?.let { File(pendingDir(hash), it).delete() } }
            cacheDir(hash).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        }
    }

    /** Forgets the in-memory read grants of an account whose local data is being deleted. */
    fun forgetAccount(userHash: String) {
        grants.keys.removeIf { it.startsWith("$userHash/") }
    }

    /**
     * One background pass for photo [mediaId] of [userId]: upload, then (if a delete was queued meanwhile)
     * delete. Runs under the account's sync lock, so duplicate workers serialise and sign-out deletion
     * waits for it. Replay-safe: the upload always carries the same id.
     */
    suspend fun process(userId: String, mediaId: String): MediaWorkOutcome = locks.forUser(userId).withLock {
        val open = databases.current.value?.takeIf { it.userId == userId } ?: return@withLock MediaWorkOutcome.UNAVAILABLE
        val hash = UserDatabaseManagerImpl.userHash(userId)
        repeat(MAX_STEPS) {
            val row = open.database.pendingMediaDao().findById(mediaId) ?: return@withLock MediaWorkOutcome.DONE
            if (row.state != PendingMediaState.PENDING) return@withLock MediaWorkOutcome.DONE
            val kind = MediaKind.fromWire(row.kind) ?: run {
                markRejected(open.database, row, "UNKNOWN_KIND")
                return@withLock MediaWorkOutcome.DONE
            }
            val outcome = when (row.operation) {
                PendingMediaOperation.UPLOAD -> upload(open, hash, row, kind)
                PendingMediaOperation.DELETE -> remove(open, hash, row, kind)
                else -> {
                    markRejected(open.database, row, "UNKNOWN_OPERATION")
                    MediaWorkOutcome.DONE
                }
            }
            if (outcome != null) return@withLock outcome
        }
        MediaWorkOutcome.RETRY
    }

    /** Null when the photo still has work (a delete queued during the upload). */
    private suspend fun upload(open: OpenUserDatabase, hash: String, row: PendingMediaEntity, kind: MediaKind): MediaWorkOutcome? {
        val file = row.fileName?.let { File(pendingDir(hash), it) }
        if (file == null || !file.isFile) {
            markRejected(open.database, row, LOCAL_FILE_MISSING)
            return MediaWorkOutcome.DONE
        }
        val response = try {
            api.upload(open.userId, kind, row.id, fieldsOf(row), file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncApiException) {
            if (e is SyncApiException.Api && !e.retryable && e.rawCode == MEDIA_DELETED) {
                // Deleted elsewhere after an earlier attempt stored it: nothing left to upload.
                finish(open.database, hash, row)
                return MediaWorkOutcome.DONE
            }
            return failure(open.database, row, e)
        } catch (e: IOException) {
            return recordAttempt(open.database, row, "IO")
        }
        grants[grantKey(hash, row.id)] = MediaReadGrant(response.mediaId, response.readUrl, response.readUrlExpiresAt)

        val confirmed = open.database.withTransaction {
            val current = open.database.pendingMediaDao().findById(row.id)
            if (current?.operation != PendingMediaOperation.UPLOAD) return@withTransaction false
            insertSyncedIfAbsent(open.database, current, kind, response.objectKey)
            open.database.pendingMediaDao().delete(row.id)
            true
        }
        if (!confirmed) return null
        withContext(Dispatchers.IO) {
            moveToCache(file, cacheFile(hash, row.id))
            trimCache(hash)
        }
        return MediaWorkOutcome.DONE
    }

    private suspend fun remove(open: OpenUserDatabase, hash: String, row: PendingMediaEntity, kind: MediaKind): MediaWorkOutcome {
        try {
            api.delete(open.userId, kind, row.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncApiException) {
            val gone = e is SyncApiException.Api && e.httpStatus == HTTP_NOT_FOUND && !e.retryable
            if (e is SyncApiException.SessionRequired) return MediaWorkOutcome.SESSION_REQUIRED
            // Any other refusal is retried: a delete the user asked for is never stranded as "rejected".
            if (!gone) return recordAttempt(open.database, row, errorCode(e))
        }
        open.database.withTransaction {
            when (kind) {
                MediaKind.EXERCISE -> open.database.exercisePhotoDao().findById(row.id)?.takeIf { it.deletedAt == null }?.let {
                    val at = maxOf(now(), it.updatedAt + 1)
                    open.database.exercisePhotoDao().upsert(it.copy(deletedAt = at, updatedAt = at))
                }
                MediaKind.PROGRESS -> open.database.progressPhotoDao().findById(row.id)?.takeIf { it.deletedAt == null }?.let {
                    val at = maxOf(now(), it.updatedAt + 1)
                    open.database.progressPhotoDao().upsert(it.copy(deletedAt = at, updatedAt = at))
                }
            }
            open.database.pendingMediaDao().delete(row.id)
        }
        withContext(Dispatchers.IO) { cacheFile(hash, row.id).delete() }
        grants.remove(grantKey(hash, row.id))
        return MediaWorkOutcome.DONE
    }

    private fun errorCode(e: SyncApiException): String = when (e) {
        is SyncApiException.Api -> e.rawCode.ifBlank { "HTTP ${e.httpStatus}" }
        is SyncApiException.SessionRequired -> "SESSION_REQUIRED"
        is SyncApiException.SessionRejected -> "SESSION_REJECTED"
        is SyncApiException.Transport -> "NETWORK"
        is SyncApiException.Malformed -> "MALFORMED_RESPONSE"
    }

    private suspend fun failure(db: GymLogDatabase, row: PendingMediaEntity, e: SyncApiException): MediaWorkOutcome = when (e) {
        is SyncApiException.SessionRequired -> MediaWorkOutcome.SESSION_REQUIRED
        is SyncApiException.Api -> if (e.retryable) {
            recordAttempt(db, row, e.rawCode.ifBlank { "HTTP ${e.httpStatus}" })
        } else {
            markRejected(db, row, e.rawCode.ifBlank { "HTTP ${e.httpStatus}" })
            MediaWorkOutcome.DONE
        }
        is SyncApiException.SessionRejected -> recordAttempt(db, row, "SESSION_REJECTED")
        is SyncApiException.Transport -> recordAttempt(db, row, "NETWORK")
        is SyncApiException.Malformed -> recordAttempt(db, row, "MALFORMED_RESPONSE")
    }

    /** Updates the row only if nobody changed its operation meanwhile (e.g. the user deleted it). */
    private suspend fun recordAttempt(db: GymLogDatabase, row: PendingMediaEntity, error: String): MediaWorkOutcome {
        updateIfUnchanged(db, row) { it.copy(attempts = it.attempts + 1, lastError = error) }
        return MediaWorkOutcome.RETRY
    }

    private suspend fun markRejected(db: GymLogDatabase, row: PendingMediaEntity, code: String) =
        updateIfUnchanged(db, row) { it.copy(state = PendingMediaState.REJECTED, lastError = code, attempts = it.attempts + 1) }

    private suspend fun updateIfUnchanged(db: GymLogDatabase, row: PendingMediaEntity, change: (PendingMediaEntity) -> PendingMediaEntity) {
        db.withTransaction {
            val current = db.pendingMediaDao().findById(row.id) ?: return@withTransaction
            if (current.operation == row.operation && current.state == PendingMediaState.PENDING) db.pendingMediaDao().upsert(change(current))
        }
    }

    private suspend fun finish(db: GymLogDatabase, hash: String, row: PendingMediaEntity) {
        db.withTransaction {
            val current = db.pendingMediaDao().findById(row.id)
            if (current?.operation == row.operation) db.pendingMediaDao().delete(row.id)
        }
        withContext(Dispatchers.IO) { row.fileName?.let { File(pendingDir(hash), it).delete() } }
    }

    /** The confirmed server row, written locally only if pull has not delivered it (pull replaces it later). */
    private suspend fun insertSyncedIfAbsent(db: GymLogDatabase, row: PendingMediaEntity, kind: MediaKind, objectKey: String) {
        val stamp = now()
        val url = "$READ_ROUTE${row.id}"
        when (kind) {
            MediaKind.EXERCISE -> if (db.exercisePhotoDao().findById(row.id) == null) {
                db.exercisePhotoDao().upsert(
                    ExercisePhotoEntity(row.id, stamp, null, requireNotNull(row.exerciseId), url, objectKey, serverVersion = null),
                )
            }
            MediaKind.PROGRESS -> if (db.progressPhotoDao().findById(row.id) == null) {
                db.progressPhotoDao().upsert(
                    ProgressPhotoEntity(
                        row.id, stamp, null, url, objectKey,
                        fecha = requireNotNull(row.fecha), angulo = requireNotNull(row.angulo), nota = row.nota, serverVersion = null,
                    ),
                )
            }
        }
    }

    private fun fieldsOf(row: PendingMediaEntity): Map<String, String> = buildMap {
        row.exerciseId?.let { put("exerciseId", it) }
        row.fecha?.let { put("fecha", it.toString()) }
        row.angulo?.let { put("angulo", it) }
        row.nota?.let { put("nota", it) }
    }

    // ── Files ──

    private fun pendingDir(hash: String) = File(filesRoot, "users/$hash/media/pending")

    private fun cacheDir(hash: String) = File(cacheRoot, "users/$hash/media")

    private fun cacheFile(hash: String, mediaId: String) = File(cacheDir(hash), "${fileSafe(mediaId)}$JPEG")

    /** Evicts least recently used copies until the account's cache fits [maxCacheBytes]. */
    private fun trimCache(hash: String) {
        val files = cacheDir(hash).listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxCacheBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= maxCacheBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun grantKey(hash: String, mediaId: String) = "$hash/$mediaId"

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.path + ".part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            throw IOException("Could not store the downloaded image")
        }
    }

    private fun moveToCache(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            runCatching { source.copyTo(target, overwrite = true) }
            source.delete()
        }
    }

    private fun PendingMediaEntity.toItem(): MediaItem? {
        val owner = when (MediaKind.fromWire(kind)) {
            MediaKind.EXERCISE -> MediaOwner.Exercise(exerciseId ?: return null)
            MediaKind.PROGRESS -> MediaOwner.Progress(fecha ?: return null, angulo ?: return null, nota)
            null -> return null
        }
        val status = if (state == PendingMediaState.REJECTED) {
            MediaStatus.Rejected(lastError ?: "REJECTED")
        } else {
            MediaStatus.PendingUpload(attempts, lastError)
        }
        return MediaItem(id, owner, status, objectKey = null)
    }

    private fun newDelete(
        id: String,
        kind: MediaKind,
        exerciseId: String? = null,
        fecha: Long? = null,
        angulo: String? = null,
        nota: String? = null,
    ) = PendingMediaEntity(
        id = id, kind = kind.wire, operation = PendingMediaOperation.DELETE, state = PendingMediaState.PENDING,
        exerciseId = exerciseId, fecha = fecha, angulo = angulo, nota = nota, fileName = null,
        attempts = 0, lastError = null, createdAt = now(),
    )

    private fun <T, R> Flow<List<T>>.mapList(transform: (T) -> R): Flow<List<R>> = map { list -> list.map(transform) }

    companion object {
        /** `mediaReadRoute`: the authenticated app route stored as `url` of v1 rows (never a public URL). */
        const val READ_ROUTE = "/api/media/read/"
        const val LOCAL_FILE_MISSING = "LOCAL_FILE_MISSING"
        private const val MEDIA_DELETED = "MEDIA_DELETED"
        private const val HTTP_NOT_FOUND = 404
        private const val JPEG = ".jpg"
        private const val SOURCE = ".src"
        private const val COPY_BUFFER = 64 * 1024
        private const val MAX_NOTE_CHARS = 2000
        private const val MAX_STEPS = 3

        const val DEFAULT_CACHE_BYTES = 100L * 1024 * 1024

        /** A grant is not used in its last 30 s (download time, clock skew). */
        const val GRANT_SAFETY_MILLIS = 30_000L
        private const val ORPHAN_AGE_MILLIS = 60 * 60_000L

        /** Ids are server-validated record ids; anything outside a safe file-name alphabet is hex-encoded. */
        private fun fileSafe(id: String): String =
            if (id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) id else id.toByteArray().joinToString("") { "%02x".format(it) }
    }
}
