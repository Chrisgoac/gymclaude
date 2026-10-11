package com.chrisgoac.gymlog.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.achievement.PersonalRecord
import com.chrisgoac.gymlog.domain.stats.Streak
import com.chrisgoac.gymlog.feature.achievements.R
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Logros: week streak, the milestone catalog (locked/unlocked) and the personal records. Back by callback. */
@Composable
fun AchievementsScreen(viewModel: AchievementsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AchievementsContent(state, onBack, viewModel::retry, modifier)
}

@Composable
internal fun AchievementsContent(state: AchievementsUiState, onBack: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.achievements_title)) {
            HierroHeaderAction(stringResource(R.string.achievements_back), onBack)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val streak = state.streak
            when {
                state.status == AchievementsStatus.Failed -> item(key = "failed") {
                    HierroErrorState(
                        message = stringResource(R.string.achievements_error_message),
                        retryLabel = stringResource(R.string.achievements_retry),
                        onRetry = onRetry,
                        title = stringResource(R.string.achievements_error_title),
                    )
                }
                state.status == AchievementsStatus.Loading || streak == null -> item(key = "loading") {
                    HierroLoadingState(stringResource(R.string.achievements_loading))
                }
                else -> {
                    streakSection(streak)
                    milestonesSection(state)
                    recordsSection(state.records, state.zone)
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    HierroText(text.uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted)
}

private fun LazyListScope.streakSection(streak: Streak) {
    item(key = "streak-heading") { SectionHeading(stringResource(R.string.achievements_section_streak)) }
    item(key = "streak") {
        val label = stringResource(R.string.achievements_streak_current_label)
        val current = pluralStringResource(R.plurals.achievements_streak_current, streak.weeks.current, streak.weeks.current)
        val best = pluralStringResource(R.plurals.achievements_streak_best, streak.weeks.best, streak.weeks.best)
        val target = pluralStringResource(R.plurals.achievements_streak_target, streak.weeklyTarget, streak.weeklyTarget)
        val days = pluralStringResource(R.plurals.achievements_streak_days, streak.days, streak.days)
        // One spoken line for the card; the visible text is split over several lines.
        HierroCard(Modifier.semantics(mergeDescendants = true) { contentDescription = listOf(label, current, best, target, days).joinToString(". ") }) {
            HierroText(label.uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
            HierroText(current, style = HierroTheme.typography.display)
            HierroText(best)
            HierroText(target, color = HierroTheme.colors.textMuted)
            HierroText(days, color = HierroTheme.colors.textMuted)
        }
    }
}

private fun LazyListScope.milestonesSection(state: AchievementsUiState) {
    item(key = "milestones-heading") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeading(stringResource(R.string.achievements_section_milestones))
            HierroText(
                stringResource(R.string.achievements_milestones_summary, state.unlockedCount, state.milestones.size),
                color = HierroTheme.colors.textMuted,
            )
        }
    }
    items(state.milestones, key = { "milestone-${it.key}" }) { milestone ->
        // Locked/unlocked is stated in text (never colour or opacity alone) and in the single spoken line.
        val status = milestone.unlockedAt?.let { stringResource(R.string.achievements_unlocked_on, AchievementsFormat.date(it, state.zone)) }
            ?: stringResource(R.string.achievements_locked)
        HierroCard(Modifier.semantics(mergeDescendants = true) { contentDescription = "${milestone.title}. ${milestone.description}. $status" }) {
            HierroText(milestone.title, style = HierroTheme.typography.title, color = if (milestone.unlocked) HierroTheme.colors.ink else HierroTheme.colors.textMuted)
            HierroText(milestone.description, color = HierroTheme.colors.textMuted)
            HierroText(status, style = HierroTheme.typography.label, color = if (milestone.unlocked) HierroTheme.colors.accent else HierroTheme.colors.textMuted)
        }
    }
}

private fun LazyListScope.recordsSection(records: List<PersonalRecord>, zone: ZoneId) {
    item(key = "records-heading") { SectionHeading(stringResource(R.string.achievements_section_records)) }
    if (records.isEmpty()) {
        item(key = "records-empty") { HierroText(stringResource(R.string.achievements_records_empty), color = HierroTheme.colors.textMuted) }
    } else {
        items(records, key = { "record-${it.exerciseId}" }) { record ->
            val weight = AchievementsFormat.kg(record.weight)
            val date = AchievementsFormat.date(record.achievedAt, zone)
            val spoken = stringResource(R.string.achievements_record_spoken, record.name, weight, date)
            HierroCard(Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }) {
                HierroText(record.name, style = HierroTheme.typography.title)
                HierroText(stringResource(R.string.achievements_record_value, weight, date), color = HierroTheme.colors.textMuted)
            }
        }
    }
}

/** Spanish number and date formatting (the app is Spanish-only), independent of the device locale. */
internal object AchievementsFormat {
    private val ES: Locale = Locale.forLanguageTag("es-ES")
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy", ES)

    fun kg(value: Double): String =
        NumberFormat.getNumberInstance(ES).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 0
            isGroupingUsed = false
            roundingMode = RoundingMode.HALF_UP
        }.format(value)

    fun date(millis: Long, zone: ZoneId): String = DATE.format(Instant.ofEpochMilli(millis).atZone(zone))
}
