package com.chrisgoac.gymlog.achievements

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.achievement.AchievementRepository
import com.chrisgoac.gymlog.domain.achievement.AchievementRules
import com.chrisgoac.gymlog.domain.achievement.PersonalRecord
import com.chrisgoac.gymlog.domain.stats.StatsRepository
import com.chrisgoac.gymlog.domain.stats.Streak
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import java.time.ZoneId

private const val STOP_TIMEOUT_MILLIS = 5_000L

enum class AchievementsStatus { Loading, Ready, Failed }

/** A catalog milestone; [unlockedAt] null = locked. */
data class MilestoneUi(val key: String, val title: String, val description: String, val unlockedAt: Long?) {
    val unlocked: Boolean get() = unlockedAt != null
}

data class AchievementsUiState(
    val status: AchievementsStatus = AchievementsStatus.Loading,
    val streak: Streak? = null,
    /** Every catalog milestone in catalog order, locked ones included. */
    val milestones: List<MilestoneUi> = emptyList(),
    val records: List<PersonalRecord> = emptyList(),
    /** Zone the dates are shown in (the device's). */
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val unlockedCount: Int get() = milestones.count { it.unlocked }
}

/**
 * Streak, milestones (the whole catalog, locked or unlocked) and personal records. Reads only: the
 * unlocked state is whatever reconciliation stored, which runs outside this screen (after finishing a
 * workout, a history edit or a sync pull), so opening it never writes. Recomputes are rate limited so a
 * burst of writes costs one pass.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AchievementsViewModel(
    private val stats: StatsRepository,
    private val achievements: AchievementRepository,
    private val zone: () -> ZoneId,
    private val recomputeIntervalMillis: Long = RECOMPUTE_INTERVAL_MILLIS,
) : ViewModel() {
    private val attempt = MutableStateFlow(0)

    val state: StateFlow<AchievementsUiState> = attempt.flatMapLatest {
        combine(stats.observeStreak(), achievements.observeUnlocked(), achievements.observePersonalRecords()) { streak, unlocked, records ->
            val unlockedAt = unlocked.associate { it.key to it.unlockedAt }
            AchievementsUiState(
                status = AchievementsStatus.Ready,
                streak = streak,
                milestones = AchievementRules.catalog.map { MilestoneUi(it.key, it.title, it.description, unlockedAt[it.key]) },
                records = records,
                zone = zone(),
            )
        }
            .rateLimited(recomputeIntervalMillis)
            .onStart { emit(AchievementsUiState(AchievementsStatus.Loading)) }
            .catch { emit(AchievementsUiState(AchievementsStatus.Failed)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AchievementsUiState())

    fun retry() = attempt.update { it + 1 }

    companion object {
        const val RECOMPUTE_INTERVAL_MILLIS = 150L
    }
}

private fun <T> Flow<T>.rateLimited(intervalMillis: Long): Flow<T> =
    conflate().transform { value ->
        emit(value)
        delay(intervalMillis)
    }
