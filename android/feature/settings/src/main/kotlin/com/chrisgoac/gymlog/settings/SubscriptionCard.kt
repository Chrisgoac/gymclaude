package com.chrisgoac.gymlog.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.feature.settings.R
import java.time.ZoneId

/** Settings entry for GymLog Pro: the current plan and its date, the way into the Pro screen and the Play manage link. */
@Composable
fun SubscriptionCard(
    plan: PlanStatus,
    onOpenPro: () -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    HierroCard(modifier) {
        HierroText(stringResource(R.string.settings_sub_title).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        HierroText(planSummary(plan, zone))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HierroButton(stringResource(R.string.settings_sub_view_pro), onOpenPro, Modifier.fillMaxWidth())
            if (plan.hasSubscription) {
                HierroButton(stringResource(R.string.settings_sub_manage), onManage, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
            }
        }
    }
}
