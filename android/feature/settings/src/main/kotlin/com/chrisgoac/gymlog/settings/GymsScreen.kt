package com.chrisgoac.gymlog.settings

import com.chrisgoac.gymlog.feature.settings.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroEmptyState
import com.chrisgoac.gymlog.designsystem.component.HierroErrorState
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroLoadingState
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.settings.GymItem
import com.chrisgoac.gymlog.domain.settings.GymNameProblem

/** Gym management: create, rename, archive, reorder, default gym and delete (with confirmation). */
@Composable
fun GymsScreen(viewModel: GymsViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.settings_gyms_title)) { HierroHeaderAction(stringResource(R.string.settings_back), onBack) }
        when (state.status) {
            GymsLoadStatus.Loading -> HierroLoadingState(stringResource(R.string.settings_gyms_loading))
            GymsLoadStatus.Failed -> HierroErrorState(stringResource(R.string.settings_gyms_error_message), stringResource(R.string.settings_back), onBack, title = stringResource(R.string.settings_gyms_error_title))
            GymsLoadStatus.Ready -> GymsContent(state, viewModel)
        }
    }
}

@Composable
private fun GymsContent(state: GymsUiState, vm: GymsViewModel) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.writeFailed) {
            HierroCard {
                HierroText(stringResource(R.string.settings_gyms_write_failed), color = HierroTheme.colors.error)
                HierroButton(stringResource(R.string.settings_gyms_dismiss), vm::dismissWriteFailed, variant = HierroButtonVariant.Secondary)
            }
        }
        state.pendingDelete?.let { pending ->
            HierroCard {
                HierroText(stringResource(R.string.settings_gyms_delete_title, pending.name).uppercase(), style = HierroTheme.typography.title, color = HierroTheme.colors.error)
                HierroText(
                    if (pending.sessionCount > 0) pluralStringResource(R.plurals.settings_gyms_delete_in_use, pending.sessionCount, pending.sessionCount)
                    else stringResource(R.string.settings_gyms_delete_unused),
                )
                HierroButton(stringResource(R.string.settings_gyms_delete_keep), vm::cancelDelete)
                HierroButton(stringResource(R.string.settings_gyms_delete_confirm), vm::confirmDelete, variant = HierroButtonVariant.Secondary)
            }
        }
        state.editor?.let { editor ->
            HierroCard {
                HierroTextField(
                    value = editor.name,
                    onValueChange = vm::onName,
                    label = stringResource(R.string.settings_gyms_name_label),
                    errorMessage = editor.error?.let { stringResource(if (it == GymNameProblem.BLANK) R.string.settings_gyms_name_blank else R.string.settings_gyms_name_long) },
                )
                HierroButton(stringResource(R.string.settings_gyms_save), vm::save)
                HierroButton(stringResource(R.string.settings_gyms_cancel), vm::cancelEdit, variant = HierroButtonVariant.Secondary)
            }
        } ?: HierroButton(stringResource(R.string.settings_gyms_add), vm::startCreate)

        if (state.gyms.isEmpty()) {
            HierroEmptyState(stringResource(R.string.settings_gyms_empty_title), stringResource(R.string.settings_gyms_empty_message))
        }
        state.gyms.forEachIndexed { index, gym ->
            GymCard(gym, isDefault = gym.id == state.defaultGymId, canUp = index > 0, canDown = index < state.gyms.lastIndex, vm = vm)
        }
    }
}

@Composable
private fun GymCard(gym: GymItem, isDefault: Boolean, canUp: Boolean, canDown: Boolean, vm: GymsViewModel) {
    HierroCard {
        HierroText(gym.name, style = HierroTheme.typography.title)
        val facts = buildList {
            if (isDefault) add(stringResource(R.string.settings_gyms_default_badge))
            if (gym.archived) add(stringResource(R.string.settings_gyms_archived_badge))
            add(pluralStringResource(R.plurals.settings_gyms_sessions, gym.sessionCount, gym.sessionCount))
        }
        HierroText(facts.joinToString(" · "), color = HierroTheme.colors.textMuted)
        MoveActions(gym.name, canUp, canDown, stringResource(R.string.settings_gyms_move_up), stringResource(R.string.settings_gyms_move_down), { vm.move(gym.id, -1) }, { vm.move(gym.id, 1) })
        HierroButton(stringResource(R.string.settings_gyms_rename), { vm.startRename(gym.id) }, variant = HierroButtonVariant.Secondary, contentDescription = stringResource(R.string.settings_gyms_rename_named, gym.name))
        if (!gym.archived) {
            if (isDefault) {
                HierroButton(stringResource(R.string.settings_gyms_unset_default), { vm.setDefault(null) }, variant = HierroButtonVariant.Secondary, contentDescription = stringResource(R.string.settings_gyms_unset_default_named, gym.name))
            } else {
                HierroButton(stringResource(R.string.settings_gyms_set_default), { vm.setDefault(gym.id) }, variant = HierroButtonVariant.Secondary, contentDescription = stringResource(R.string.settings_gyms_set_default_named, gym.name))
            }
        }
        HierroButton(
            stringResource(if (gym.archived) R.string.settings_gyms_restore else R.string.settings_gyms_archive),
            { vm.setArchived(gym.id, !gym.archived) },
            variant = HierroButtonVariant.Secondary,
            contentDescription = stringResource(if (gym.archived) R.string.settings_gyms_restore_named else R.string.settings_gyms_archive_named, gym.name),
        )
        HierroButton(stringResource(R.string.settings_gyms_delete), { vm.requestDelete(gym.id) }, variant = HierroButtonVariant.Secondary, contentDescription = stringResource(R.string.settings_gyms_delete_named, gym.name))
    }
}
