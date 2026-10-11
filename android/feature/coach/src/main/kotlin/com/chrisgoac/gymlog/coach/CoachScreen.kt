package com.chrisgoac.gymlog.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.coach.CoachMessage
import com.chrisgoac.gymlog.domain.coach.CoachReportCategory
import com.chrisgoac.gymlog.domain.coach.CoachRole
import com.chrisgoac.gymlog.feature.coach.R

/** Everything the Coach screen can ask for; the screen itself holds no state. */
class CoachActions(
    val onBack: () -> Unit,
    val onDraftChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onSuggestion: (String) -> Unit,
    val onRetry: () -> Unit,
    val onDismissTurn: () -> Unit,
    val onDismissNotice: () -> Unit,
    val onReport: (String) -> Unit,
    val onReportCategory: (CoachReportCategory) -> Unit,
    val onSubmitReport: () -> Unit,
    val onCloseReport: () -> Unit,
    /** Opens the Pro screen from the locked state; null hides the offer. */
    val onOpenPro: (() -> Unit)? = null,
)

@Composable
fun CoachScreen(viewModel: CoachViewModel, onBack: () -> Unit, modifier: Modifier = Modifier, onOpenPro: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsState()
    CoachContent(
        state,
        CoachActions(
            onBack = onBack,
            onDraftChange = viewModel::onDraftChange,
            onSend = viewModel::send,
            onSuggestion = viewModel::sendSuggestion,
            onRetry = viewModel::retry,
            onDismissTurn = viewModel::dismissTurn,
            onDismissNotice = viewModel::dismissNotice,
            onReport = viewModel::openReport,
            onReportCategory = viewModel::selectReportCategory,
            onSubmitReport = viewModel::submitReport,
            onCloseReport = viewModel::closeReport,
            onOpenPro = onOpenPro,
        ),
        modifier,
    )
}

@Composable
internal fun CoachContent(state: CoachUiState, actions: CoachActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.coach_title)) {
            HierroHeaderAction(stringResource(R.string.coach_back), actions.onBack)
        }
        when (state.access) {
            CoachAccess.CHECKING -> HierroLoadingState(stringResource(R.string.coach_loading))
            CoachAccess.LOCKED -> HierroEmptyState(
                title = stringResource(R.string.coach_locked_title),
                message = stringResource(R.string.coach_locked_message),
                modifier = Modifier.padding(16.dp),
                actionLabel = actions.onOpenPro?.let { stringResource(R.string.coach_locked_action) },
                onAction = actions.onOpenPro,
            )
            CoachAccess.AVAILABLE -> Conversation(state, actions)
        }
    }
    state.report?.let { ReportDialog(it, actions) }
}

@Composable
private fun Conversation(state: CoachUiState, actions: CoachActions) {
    val listState = rememberLazyListState()
    val itemCount = state.messages.size + if (state.turn != null) 1 else 0
    // Follow the conversation as messages and the streamed answer grow.
    LaunchedEffect(itemCount, state.turn?.text?.length) { if (itemCount > 0) listState.scrollToItem(itemCount - 1) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.threadLoaded && state.messages.isEmpty() && state.turn == null) {
                item(key = "empty") {
                    HierroEmptyState(stringResource(R.string.coach_empty_title), stringResource(R.string.coach_empty_message))
                }
            }
            items(state.messages, key = { it.id }) { message -> MessageBubble(message, actions.onReport) }
            state.turn?.let { turn -> item(key = "turn") { TurnBubble(turn, actions) } }
            item(key = "suggestions") { Suggestions(state, actions.onSuggestion) }
            item(key = "disclaimer") {
                HierroText(stringResource(R.string.coach_disclaimer), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
            }
        }
        Composer(state, actions)
    }
}

@Composable
private fun MessageBubble(message: CoachMessage, onReport: (String) -> Unit) {
    val mine = message.role == CoachRole.USER
    val who = stringResource(if (mine) R.string.coach_you else R.string.coach_assistant)
    val colors = HierroTheme.colors
    val spoken = stringResource(R.string.coach_spoken_message, who, message.text)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth(BUBBLE_WIDTH)
                .background(if (mine) colors.accent else colors.surface)
                .border(HierroShape.border, colors.border)
                .padding(12.dp)
                .clearAndSetSemantics { contentDescription = spoken },
        ) {
            Column {
                HierroText(who.uppercase(), style = HierroTheme.typography.label, color = if (mine) colors.onAccent else colors.textMuted)
                HierroText(message.text, color = if (mine) colors.onAccent else colors.ink)
            }
        }
        if (!mine) {
            HierroButton(
                stringResource(R.string.coach_report),
                { onReport(message.id) },
                variant = HierroButtonVariant.Secondary,
                contentDescription = stringResource(R.string.coach_report_description),
            )
        }
    }
}

@Composable
private fun TurnBubble(turn: CoachTurn, actions: CoachActions) {
    val colors = HierroTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth(BUBBLE_WIDTH)
                .background(colors.surface)
                .border(HierroShape.border, if (turn.phase == TurnPhase.INCOMPLETE) colors.error else colors.border)
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HierroText(stringResource(R.string.coach_assistant).uppercase(), Modifier.clearAndSetSemantics {}, HierroTheme.typography.label, colors.textMuted)
                if (turn.text.isNotEmpty()) HierroText(turn.text)
                val status = when (turn.phase) {
                    TurnPhase.SENDING -> stringResource(R.string.coach_thinking)
                    TurnPhase.STREAMING, TurnPhase.COMPLETING -> stringResource(R.string.coach_writing)
                    TurnPhase.INCOMPLETE -> stringResource(R.string.coach_incomplete)
                }
                // Only the status is a live region: announcing every streamed chunk would be noise.
                HierroText(
                    status,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    HierroTheme.typography.label,
                    if (turn.phase == TurnPhase.INCOMPLETE) colors.error else colors.textMuted,
                )
                turn.failure?.let { HierroText(failureMessage(it), color = colors.error) }
            }
        }
        if (turn.phase == TurnPhase.INCOMPLETE) {
            if (turn.canRetry) HierroButton(stringResource(R.string.coach_retry), actions.onRetry, Modifier.fillMaxWidth())
            HierroButton(stringResource(R.string.coach_dismiss), actions.onDismissTurn, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
        }
    }
}

@Composable
private fun failureMessage(failure: CoachFailure): String = stringResource(
    when (failure) {
        CoachFailure.INTERRUPTED -> R.string.coach_failure_interrupted
        CoachFailure.BUSY -> R.string.coach_failure_busy
        CoachFailure.UNAVAILABLE, CoachFailure.PRO_REQUIRED -> R.string.coach_failure_unavailable
        CoachFailure.SESSION -> R.string.coach_failure_session
        CoachFailure.REJECTED -> R.string.coach_failure_rejected
        CoachFailure.NO_ACCOUNT -> R.string.coach_failure_no_account
    },
)

@Composable
private fun Suggestions(state: CoachUiState, onSuggestion: (String) -> Unit) {
    val enabled = state.access == CoachAccess.AVAILABLE && state.online && state.turn?.inFlight != true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HierroText(
            stringResource(R.string.coach_suggestions).uppercase(),
            Modifier.semantics { heading() },
            HierroTheme.typography.label,
            HierroTheme.colors.textMuted,
        )
        CoachViewModel.SUGGESTED_PROMPTS.forEach { prompt ->
            HierroButton(prompt, { onSuggestion(prompt) }, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary, enabled = enabled)
        }
    }
}

@Composable
private fun Composer(state: CoachUiState, actions: CoachActions) {
    val colors = HierroTheme.colors
    Column(
        Modifier.fillMaxWidth().background(colors.background).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!state.online || state.notice == CoachNotice.OFFLINE) {
            HierroText(stringResource(R.string.coach_offline), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
        }
        if (state.notice == CoachNotice.REPORT_SENT) {
            HierroText(stringResource(R.string.coach_report_sent), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            HierroButton(stringResource(R.string.coach_close), actions.onDismissNotice, variant = HierroButtonVariant.Secondary)
        }
        HierroTextField(
            value = state.draft,
            onValueChange = actions.onDraftChange,
            label = stringResource(R.string.coach_question),
            singleLine = false,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (state.canSend) actions.onSend() }),
        )
        HierroButton(stringResource(R.string.coach_send), actions.onSend, Modifier.fillMaxWidth(), enabled = state.canSend)
    }
}

@Composable
private fun ReportDialog(report: CoachReportDraft, actions: CoachActions) {
    val title = stringResource(R.string.coach_report_title)
    val selectedLabel = stringResource(R.string.coach_report_selected)
    Dialog(onDismissRequest = actions.onCloseReport) {
        HierroCard(Modifier.padding(16.dp).semantics { paneTitle = title }) {
            // Scrolls as a whole at large font sizes.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HierroText(title, Modifier.semantics { heading() }, HierroTheme.typography.title)
                HierroText(stringResource(R.string.coach_report_message))
                CoachReportCategory.entries.forEach { category ->
                    val label = stringResource(category.label())
                    val selected = report.category == category
                    HierroButton(
                        label,
                        { actions.onReportCategory(category) },
                        Modifier.fillMaxWidth().semantics { this.selected = selected },
                        variant = if (selected) HierroButtonVariant.Primary else HierroButtonVariant.Secondary,
                        contentDescription = if (selected) "$label, $selectedLabel" else label,
                    )
                }
                if (report.failure != null) {
                    HierroText(
                        stringResource(R.string.coach_report_failed),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = HierroTheme.colors.error,
                    )
                }
                HierroButton(
                    stringResource(R.string.coach_report_submit),
                    actions.onSubmitReport,
                    Modifier.fillMaxWidth(),
                    enabled = report.category != null,
                    loading = report.sending,
                )
                HierroButton(stringResource(R.string.coach_report_cancel), actions.onCloseReport, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
            }
        }
    }
}

private fun CoachReportCategory.label(): Int = when (this) {
    CoachReportCategory.INACCURATE -> R.string.coach_report_inaccurate
    CoachReportCategory.HARMFUL -> R.string.coach_report_harmful
    CoachReportCategory.MEDICAL -> R.string.coach_report_medical
    CoachReportCategory.OFFENSIVE -> R.string.coach_report_offensive
    CoachReportCategory.OTHER -> R.string.coach_report_other
}

private const val BUBBLE_WIDTH = 0.85f
