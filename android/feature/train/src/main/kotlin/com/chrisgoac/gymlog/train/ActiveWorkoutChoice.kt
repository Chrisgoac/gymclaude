package com.chrisgoac.gymlog.train

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.feature.train.R

/**
 * "Tienes un entrenamiento en curso": shown when a start (Train or Routines) ran into the active
 * workout. Host it once at the account shell so it appears over whichever tab started.
 */
@Composable
fun ActiveWorkoutChoice(viewModel: TrainHomeViewModel) {
    val sessionId by viewModel.activeChoice.collectAsState()
    if (sessionId != null) ActiveWorkoutChoiceDialog(onContinue = viewModel::continueActive, onCancel = viewModel::dismissActiveChoice)
}

@Composable
internal fun ActiveWorkoutChoiceDialog(onContinue: () -> Unit, onCancel: () -> Unit) {
    val title = stringResource(R.string.train_active_choice_title)
    // System back and a tap outside cancel, like the button.
    Dialog(onDismissRequest = onCancel) {
        HierroCard(Modifier.padding(16.dp).semantics { paneTitle = title }) {
            HierroText(title, Modifier.semantics { heading() }, HierroTheme.typography.title)
            HierroText(stringResource(R.string.train_active_choice_message))
            HierroButton(stringResource(R.string.train_active_choice_continue), onContinue, Modifier.fillMaxWidth())
            HierroButton(stringResource(R.string.train_active_choice_cancel), onCancel, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
        }
    }
}
