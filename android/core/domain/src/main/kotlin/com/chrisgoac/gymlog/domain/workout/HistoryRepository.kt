package com.chrisgoac.gymlog.domain.workout

import kotlinx.coroutines.flow.Flow

/** History window, PWA `lib/period.ts` (4 sem = 28 days, 3 meses = 90, Año = 365, Todo = no limit). */
enum class HistoryPeriod(val days: Int) {
    WEEKS_4(28),
    MONTHS_3(90),
    YEAR(365),
    ALL(0),
    ;

    /** Start instant (epoch millis) of the window; 0 = no limit. */
    fun sinceMillis(now: Long): Long = if (days == 0) 0L else now - days * DAY_MILLIS

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** [gymId] null = every gym. [query] matches routine name, exercise names and notes, ignoring case and accents. */
data class HistoryFilter(
    val period: HistoryPeriod = HistoryPeriod.ALL,
    val gymId: String? = null,
    val query: String = "",
)

data class HistoryItem(
    val sessionId: String,
    val startedAt: Long,
    val durationSeconds: Int,
    val gymId: String?,
    /** Null for no gym or a gym that no longer exists. */
    val gymName: String?,
    val routineName: String?,
    val notes: String?,
    val exerciseCount: Int,
    val setCount: Int,
    val volume: Double,
)

/** Newest-first finished sessions up to the requested limit; [hasMore] = older matches exist. */
data class HistoryPage(val items: List<HistoryItem>, val hasMore: Boolean)

/** A finished (or any live) session with its full ordered detail. */
data class HistoryDetail(
    val workout: WorkoutAggregate,
    val gymName: String?,
    val routineName: String?,
    val summary: WorkoutSummary,
)

/**
 * Read side of finished workouts plus the two history edits. Reads are scoped SQL (limit + indexes),
 * never the whole table; writes are one local mutation each and never wait for the network.
 */
interface HistoryRepository {
    fun observeHistory(filter: HistoryFilter, limit: Int): Flow<HistoryPage>

    /** The session's detail, or null while missing/deleted. Re-emits on any change of its rows. */
    fun observeDetail(sessionId: String): Flow<HistoryDetail?>

    /**
     * Moves the session to [gymId] (null = none) in one mutation; nothing is written when unchanged.
     * @throws WorkoutRowMissingException; [WorkoutStartException.GymUnavailable]
     */
    suspend fun changeGym(sessionId: String, gymId: String?)

    /**
     * Tombstones the session and every live exercise and set in ONE logical mutation (the local
     * runner chunks it past 50 operations). Deleting what is already gone is a no-op.
     */
    suspend fun deleteSession(sessionId: String)
}
