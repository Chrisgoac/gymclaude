package com.chrisgoac.gymlog.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.ai.IntelligentReport
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.feature.progress.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The Pro intelligent report: summary, strengths, what to improve and recommendations, with its date and disclaimer. */
@Composable
fun IntelligentReportScreen(viewModel: IntelligentReportViewModel, zone: () -> ZoneId, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    IntelligentReportContent(state, zone(), onBack, viewModel::refresh, modifier)
}

private val REPORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.forLanguageTag("es-ES"))

@Composable
internal fun IntelligentReportContent(
    state: IntelligentReportUiState,
    zone: ZoneId,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.progress_ai_report_title)) {
            HierroHeaderAction(stringResource(R.string.progress_ai_report_back), onBack)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.access) {
                ReportAccess.CHECKING -> item(key = "checking") { HierroLoadingState(stringResource(R.string.progress_ai_report_checking)) }
                ReportAccess.LOCKED -> item(key = "locked") {
                    HierroCard {
                        HierroText(stringResource(R.string.progress_ai_report_locked_title), Modifier.semantics { heading() }, HierroTheme.typography.label)
                        HierroText(stringResource(R.string.progress_ai_report_locked_message))
                    }
                }
                ReportAccess.AVAILABLE -> {
                    statusMessage(state)?.let { message ->
                        item(key = "status") {
                            HierroCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                HierroText(stringResource(message), color = if (state.failure != null) HierroTheme.colors.error else HierroTheme.colors.ink)
                            }
                        }
                    }
                    if (state.loading) item(key = "loading") { HierroLoadingState(stringResource(R.string.progress_ai_report_loading)) }
                    state.report?.let { report -> reportItems(report, zone) }
                    item(key = "refresh") {
                        HierroButton(
                            text = stringResource(if (state.failure?.retryable == true) R.string.progress_ai_report_retry else R.string.progress_ai_report_refresh),
                            onClick = onRefresh,
                            modifier = Modifier.fillMaxWidth(),
                            variant = HierroButtonVariant.Secondary,
                            enabled = state.canRefresh,
                        )
                    }
                }
            }
            item(key = "disclaimer") {
                HierroText(stringResource(R.string.progress_ai_report_disclaimer), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
            }
        }
    }
}

private fun statusMessage(state: IntelligentReportUiState): Int? = when {
    !state.online -> if (state.report != null) R.string.progress_ai_report_offline_cached else R.string.progress_ai_report_offline
    state.failure == null -> null
    state.failure == CoachFailure.SESSION -> R.string.progress_ai_report_failed_session
    state.failure.retryable -> R.string.progress_ai_report_failed
    else -> R.string.progress_ai_report_failed_rejected
}

private fun androidx.compose.foundation.lazy.LazyListScope.reportItems(report: IntelligentReport, zone: ZoneId) {
    item(key = "date") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HierroText(
                stringResource(R.string.progress_ai_report_generated, REPORT_DATE.format(Instant.ofEpochMilli(report.generatedAt).atZone(zone))),
                style = HierroTheme.typography.label,
            )
            if (report.cached) HierroText(stringResource(R.string.progress_ai_report_cached), color = HierroTheme.colors.textMuted)
        }
    }
    item(key = "summary") { Section(stringResource(R.string.progress_ai_report_summary), listOf(report.summary), bullets = false) }
    if (report.strengths.isNotEmpty()) item(key = "strengths") { Section(stringResource(R.string.progress_ai_report_strengths), report.strengths) }
    if (report.improvements.isNotEmpty()) item(key = "improve") { Section(stringResource(R.string.progress_ai_report_improve), report.improvements) }
    if (report.recommendations.isNotEmpty()) {
        item(key = "recommendations") { Section(stringResource(R.string.progress_ai_report_recommendations), report.recommendations) }
    }
}

@Composable
private fun Section(title: String, lines: List<String>, bullets: Boolean = true) {
    HierroCard {
        HierroText(title, Modifier.semantics { heading() }, HierroTheme.typography.label)
        lines.forEach { HierroText(if (bullets) "• $it" else it) }
    }
}
