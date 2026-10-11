package com.chrisgoac.gymlog.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.feature.settings.R

/**
 * Public pages the account screens link to. [subscriptions] is Play's subscription page for this app (with the
 * product when known), read when tapped.
 */
data class AccountLinks(
    val privacyPolicy: String,
    val accountDeletion: String,
    val subscriptions: () -> String,
)

/** Privacy: policy, web account deletion, Play subscriptions and support contact. */
@Composable
fun PrivacyScreen(
    links: AccountLinks,
    onBack: () -> Unit,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.settings_privacy_title)) {
            HierroHeaderAction(stringResource(R.string.settings_back), onBack)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinkCard(R.string.settings_privacy_policy, R.string.settings_privacy_policy_message) { onOpenUri(links.privacyPolicy) }
            LinkCard(R.string.settings_privacy_subscriptions, R.string.settings_privacy_subscriptions_message) { onOpenUri(links.subscriptions()) }
            LinkCard(R.string.settings_privacy_delete_web, R.string.settings_privacy_delete_web_message) { onOpenUri(links.accountDeletion) }
            HierroCard {
                HierroText(stringResource(R.string.settings_privacy_support_title).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
                HierroText(stringResource(R.string.settings_privacy_support))
            }
        }
    }
}

@Composable
private fun LinkCard(title: Int, message: Int, onOpen: () -> Unit) {
    val label = stringResource(title)
    HierroCard {
        HierroText(label.uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        HierroText(stringResource(message))
        HierroButton(
            stringResource(R.string.settings_privacy_open),
            onOpen,
            variant = HierroButtonVariant.Secondary,
            contentDescription = "${stringResource(R.string.settings_privacy_open)}: $label",
        )
    }
}
