package com.chrisgoac.gymlog.domain.ai

/**
 * Pro planning features: AI mesocycle drafts (reviewed, then saved as the account's own synced rows) and
 * the intelligent progress report (server-owned, never synced). Both need the network; the server enforces
 * Pro and callers only gate the presentation. Calls fail with
 * [com.chrisgoac.gymlog.domain.coach.CoachException] and the same [com.chrisgoac.gymlog.domain.coach.CoachFailure]
 * reasons as the Coach (a malformed model answer is a retryable `UNAVAILABLE`).
 */

/** `MESO_OBJECTIVES`, as in the PWA generator. */
enum class MesocycleGoal(val wire: String, val label: String) {
    HIPERTROFIA("hipertrofia", "Hipertrofia"),
    FUERZA("fuerza", "Fuerza"),
    GENERAL("general", "General"),
}

data class MesocycleParams(
    val goal: MesocycleGoal = MesocycleGoal.HIPERTROFIA,
    val daysPerWeek: Int = 4,
    val weeks: Int = 6,
    val minutesPerSession: Int = 60,
) {
    companion object {
        val DAYS = 2..6
        val WEEKS = 4..8
        val MINUTES = 30..90
        const val MINUTES_STEP = 15
    }

    val isValid: Boolean get() = daysPerWeek in DAYS && weeks in WEEKS && minutesPerSession in MINUTES
}

data class MesocycleWeek(val week: Int, val deload: Boolean, val adjustment: String)

/**
 * One exercise of a draft day. [exerciseId] is the account's OWN exercise id, mapped on the device from the
 * catalog name the server returned (the server never sends ids). [key] is a stable key for the list only.
 */
data class MesocycleDraftExercise(
    val key: String,
    val exerciseId: String,
    val name: String,
    val sets: Int,
    val reps: Int,
    val restSeconds: Int,
)

data class MesocycleDraftDay(val key: String, val name: String, val exercises: List<MesocycleDraftExercise>)

/** A generated mesocycle under review: nothing is stored until [MesocycleRepository.save]. */
data class MesocycleDraft(
    val name: String,
    val goal: String,
    val weeks: Int,
    val daysPerWeek: Int,
    val notes: String?,
    val progression: List<MesocycleWeek>,
    val days: List<MesocycleDraftDay>,
    /** Exercises the model proposed that are not in the account's catalog (left out). */
    val omitted: List<String>,
) {
    companion object {
        const val MAX_NAME_CHARS = 120
        val SETS = 1..10
        val REPS = 1..50
    }

    /** Savable: a name and at least one exercise in every day. */
    val canSave: Boolean get() = name.isNotBlank() && days.isNotEmpty() && days.all { it.exercises.isNotEmpty() }
}

class MesocycleSaveException(message: String) : Exception(message)

interface MesocycleRepository {
    /**
     * Asks the server for a draft. Reusing [clientRequestId] (a retry after a failure or process death) never
     * pays for a second generation: the server returns the draft it already made for that id.
     */
    suspend fun generate(clientRequestId: String, params: MesocycleParams): MesocycleDraft

    /**
     * Saves the reviewed [draft] as ONE local mutation (outbox, synced later): the mesocycle, one routine per
     * day linked by `mesocycleId` (after the existing routines) and their exercises in order, all with fresh
     * local ids. Returns the new mesocycle id.
     * @throws MesocycleSaveException when the draft cannot be saved (not [MesocycleDraft.canSave], or an
     * exercise no longer exists).
     */
    suspend fun save(draft: MesocycleDraft): String
}

/** The server's report over the current local data. [generatedAt] is older than now when it was cached. */
data class IntelligentReport(
    val id: String,
    val generatedAt: Long,
    val cached: Boolean,
    val summary: String,
    val strengths: List<String>,
    val improvements: List<String>,
    val recommendations: List<String>,
)

interface IntelligentReportRepository {
    /** The report for the account's current data (the server returns its cached one when nothing changed). */
    suspend fun generate(): IntelligentReport

    /** The last report fetched for the open account in this process, shown while offline; null if none. */
    fun lastReport(): IntelligentReport?
}
