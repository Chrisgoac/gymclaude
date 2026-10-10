package com.chrisgoac.gymlog.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

// Mirrors lib/api/v1/contracts/sync.ts and contracts/v1/README.md.

/** Strict codec: unknown keys are errors. Used for fixtures and for encoding requests. */
val ContractJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
    classDiscriminator = "table"
}

/** Lenient codec for server responses at runtime, so a field added by a newer server does not brick old clients. */
val ResponseJson: Json = Json(from = ContractJson) {
    ignoreUnknownKeys = true
}

private val CANONICAL_VERSION = Regex("^(0|[1-9][0-9]{0,18})$")

/** PostgreSQL bigint as a canonical base-10 decimal JSON string (never a number); must fit in a Long. */
object ServerVersionSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ServerVersion", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Long {
        val text = decoder.decodeString()
        return (if (CANONICAL_VERSION.matches(text)) text.toLongOrNull() else null)
            ?: throw kotlinx.serialization.SerializationException("Invalid serverVersion: \"$text\"")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        require(value >= 0) { "serverVersion must not be negative" }
        encoder.encodeString(value.toString())
    }
}

@JvmInline
@Serializable
value class MutationId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class DeviceId(val value: String) {
    override fun toString(): String = value
}

/** Client -> server write. Polymorphic on the `table` wire field; an unknown table fails decoding. */
@Serializable
sealed interface SyncOperation {
    val table: SyncTable
    val record: SyncRecord
    val baseServerVersion: Long?
}

/** Server -> client change: the full record plus the row's serverVersion. */
@Serializable
sealed interface SyncChange {
    val table: SyncTable
    val record: SyncRecord
    val serverVersion: Long
}

@Serializable
@SerialName("exercises")
data class ExerciseOperation(
    override val record: ExerciseRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.EXERCISES
}

@Serializable
@SerialName("exercises")
data class ExerciseChange(
    override val record: ExerciseRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.EXERCISES
}

@Serializable
@SerialName("routines")
data class RoutineOperation(
    override val record: RoutineRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.ROUTINES
}

@Serializable
@SerialName("routines")
data class RoutineChange(
    override val record: RoutineRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.ROUTINES
}

@Serializable
@SerialName("routineExercises")
data class RoutineExerciseOperation(
    override val record: RoutineExerciseRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.ROUTINE_EXERCISES
}

@Serializable
@SerialName("routineExercises")
data class RoutineExerciseChange(
    override val record: RoutineExerciseRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.ROUTINE_EXERCISES
}

@Serializable
@SerialName("workoutSessions")
data class WorkoutSessionOperation(
    override val record: WorkoutSessionRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.WORKOUT_SESSIONS
}

@Serializable
@SerialName("workoutSessions")
data class WorkoutSessionChange(
    override val record: WorkoutSessionRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.WORKOUT_SESSIONS
}

@Serializable
@SerialName("loggedExercises")
data class LoggedExerciseOperation(
    override val record: LoggedExerciseRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.LOGGED_EXERCISES
}

@Serializable
@SerialName("loggedExercises")
data class LoggedExerciseChange(
    override val record: LoggedExerciseRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.LOGGED_EXERCISES
}

@Serializable
@SerialName("loggedSets")
data class LoggedSetOperation(
    override val record: LoggedSetRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.LOGGED_SETS
}

@Serializable
@SerialName("loggedSets")
data class LoggedSetChange(
    override val record: LoggedSetRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.LOGGED_SETS
}

@Serializable
@SerialName("gyms")
data class GymOperation(
    override val record: GymRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.GYMS
}

@Serializable
@SerialName("gyms")
data class GymChange(
    override val record: GymRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.GYMS
}

@Serializable
@SerialName("exercisePhotos")
data class ExercisePhotoOperation(
    override val record: ExercisePhotoRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.EXERCISE_PHOTOS
}

@Serializable
@SerialName("exercisePhotos")
data class ExercisePhotoChange(
    override val record: ExercisePhotoRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.EXERCISE_PHOTOS
}

@Serializable
@SerialName("userSettings")
data class UserSettingOperation(
    override val record: UserSettingRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.USER_SETTINGS
}

@Serializable
@SerialName("userSettings")
data class UserSettingChange(
    override val record: UserSettingRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.USER_SETTINGS
}

@Serializable
@SerialName("coachMessages")
data class CoachMessageOperation(
    override val record: CoachMessageRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.COACH_MESSAGES
}

@Serializable
@SerialName("coachMessages")
data class CoachMessageChange(
    override val record: CoachMessageRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.COACH_MESSAGES
}

@Serializable
@SerialName("bodyMetrics")
data class BodyMetricOperation(
    override val record: BodyMetricRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.BODY_METRICS
}

@Serializable
@SerialName("bodyMetrics")
data class BodyMetricChange(
    override val record: BodyMetricRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.BODY_METRICS
}

@Serializable
@SerialName("progressPhotos")
data class ProgressPhotoOperation(
    override val record: ProgressPhotoRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.PROGRESS_PHOTOS
}

@Serializable
@SerialName("progressPhotos")
data class ProgressPhotoChange(
    override val record: ProgressPhotoRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.PROGRESS_PHOTOS
}

@Serializable
@SerialName("mesocycles")
data class MesocycleOperation(
    override val record: MesocycleRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.MESOCYCLES
}

@Serializable
@SerialName("mesocycles")
data class MesocycleChange(
    override val record: MesocycleRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.MESOCYCLES
}

@Serializable
@SerialName("achievements")
data class AchievementOperation(
    override val record: AchievementRecord,
    @Serializable(with = ServerVersionSerializer::class) override val baseServerVersion: Long?,
) : SyncOperation {
    override val table: SyncTable get() = SyncTable.ACHIEVEMENTS
}

@Serializable
@SerialName("achievements")
data class AchievementChange(
    override val record: AchievementRecord,
    @Serializable(with = ServerVersionSerializer::class) override val serverVersion: Long,
) : SyncChange {
    override val table: SyncTable get() = SyncTable.ACHIEVEMENTS
}

const val MAX_MUTATIONS_PER_PUSH = 50
const val MAX_OPERATIONS_PER_MUTATION = 50

@Serializable
data class PushMutation(
    val mutationId: MutationId,
    val deviceId: DeviceId,
    val operations: List<SyncOperation>,
)

@Serializable
data class PushRequest(val mutations: List<PushMutation>)

enum class ConflictReason {
    /** The write applied, but baseServerVersion did not match the stored serverVersion. */
    STALE_BASE,

    /** The write lost the version-tuple comparison; adopt the server record on the next pull. */
    SUPERSEDED,
}

@Serializable
data class SyncConflict(
    val mutationId: MutationId,
    val table: SyncTable,
    val recordId: RecordId,
    @Serializable(with = ServerVersionSerializer::class) val serverVersion: Long,
    val reason: ConflictReason,
)

@Serializable
data class PushResponse(
    val acceptedMutationIds: List<MutationId>,
    val conflicts: List<SyncConflict>,
)

@Serializable
data class PullResponse(
    val changes: List<SyncChange>,
    /** Opaque base64url text; store and send back unchanged. */
    val cursor: String,
    val hasMore: Boolean,
)
