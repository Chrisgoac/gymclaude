package com.chrisgoac.gymlog.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.chrisgoac.gymlog.domain.billing.BillingFailure
import com.chrisgoac.gymlog.domain.billing.ProProduct
import com.chrisgoac.gymlog.domain.billing.ProductCatalog
import com.chrisgoac.gymlog.domain.billing.ProductOffer
import com.chrisgoac.gymlog.domain.billing.PurchaseStatus
import com.chrisgoac.gymlog.feature.settings.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything the Pro screen can ask for; the screen itself holds no state. */
class ProActions(
    val onBack: () -> Unit,
    val onSubscribe: (ProProduct) -> Unit,
    val onRestore: () -> Unit,
    val onManage: () -> Unit,
    val onReloadProducts: () -> Unit,
    val onDismissNotice: () -> Unit,
)

/**
 * GymLog Pro: what it unlocks (three AI features) and what stays free, the monthly / annual choice with Play's
 * localized price and period, the recurring-billing disclosure, restore and manage. [activity] is the foreground
 * `android.app.Activity` (supplied by the app; null if there is none, which disables nothing but cannot launch Play).
 * Pro is only ever granted by the server: this screen just starts Play and shows the server's answer.
 */
@Composable
fun ProScreen(
    viewModel: ProViewModel,
    onBack: () -> Unit,
    activity: () -> Any?,
    onOpenUri: (String) -> Unit,
    modifier: Modifier = Modifier,
    zone: () -> ZoneId = ZoneId::systemDefault,
) {
    val state by viewModel.state.collectAsState()
    ProContent(
        state,
        ProActions(
            onBack = onBack,
            onSubscribe = { product -> activity()?.let { viewModel.purchase(it, product) } },
            onRestore = viewModel::restorePurchases,
            onManage = { onOpenUri(viewModel.managementUri()) },
            onReloadProducts = viewModel::reloadProducts,
            onDismissNotice = viewModel::dismissNotice,
        ),
        zone(),
        modifier,
    )
}

@Composable
internal fun ProContent(state: ProUiState, actions: ProActions, zone: ZoneId, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.settings_pro_title)) {
            HierroHeaderAction(stringResource(R.string.settings_pro_back), actions.onBack)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(state, zone)
            Notice(state, actions)
            IncludesCard()
            PlansSection(state, actions)
            HierroCard {
                HierroText(stringResource(R.string.settings_pro_no_trial))
                if (state.plan.isPro) HierroText(stringResource(R.string.settings_pro_switch_note), color = HierroTheme.colors.textMuted)
            }
            if (!state.online) {
                HierroText(
                    stringResource(R.string.settings_pro_offline),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = HierroTheme.colors.error,
                )
            }
            HierroButton(
                stringResource(R.string.settings_pro_restore),
                actions.onRestore,
                Modifier.fillMaxWidth(),
                variant = HierroButtonVariant.Secondary,
                enabled = state.canTransact,
            )
            HierroButton(stringResource(R.string.settings_pro_manage), actions.onManage, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
            HierroText(stringResource(R.string.settings_pro_manage_hint), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        }
    }
}

@Composable
private fun StatusCard(state: ProUiState, zone: ZoneId) {
    HierroCard {
        HierroText(stringResource(R.string.settings_pro_status_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.textMuted)
        HierroText(planSummary(state.plan, zone))
    }
}

@Composable
private fun IncludesCard() {
    HierroCard {
        HierroText(stringResource(R.string.settings_pro_includes_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title)
        HierroText(stringResource(R.string.settings_pro_include_coach))
        HierroText(stringResource(R.string.settings_pro_include_mesocycle))
        HierroText(stringResource(R.string.settings_pro_include_report))
        HierroText(stringResource(R.string.settings_pro_free_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.label, HierroTheme.colors.accent)
        HierroText(stringResource(R.string.settings_pro_free_message), color = HierroTheme.colors.textMuted)
    }
}

@Composable
private fun PlansSection(state: ProUiState, actions: ProActions) {
    HierroText(stringResource(R.string.settings_pro_plans_title).uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title)
    when (val catalog = state.catalog) {
        ProductCatalog.Loading -> HierroLoadingState(stringResource(R.string.settings_pro_plans_loading))
        is ProductCatalog.Unavailable -> HierroCard {
            HierroText(stringResource(catalogMessage(catalog.reason)), color = HierroTheme.colors.error)
            if (catalog.reason == BillingFailure.NETWORK || catalog.reason == BillingFailure.UNKNOWN) {
                HierroButton(stringResource(R.string.settings_pro_plans_retry), actions.onReloadProducts, variant = HierroButtonVariant.Secondary)
            }
        }
        is ProductCatalog.Available -> ProProduct.entries.forEach { product ->
            catalog.offers.firstOrNull { it.product == product }?.let { PlanCard(state, it, actions) }
        }
    }
}

@Composable
private fun PlanCard(state: ProUiState, offer: ProductOffer, actions: ProActions) {
    val monthly = offer.product == ProProduct.MONTHLY
    val name = stringResource(if (monthly) R.string.settings_pro_plan_monthly else R.string.settings_pro_plan_annual)
    val price = priceText(offer)
    val current = state.plan.isPro && state.entitlement.product == offer.product
    val switching = state.plan.isPro && state.entitlement.product != null && !current
    val inFlight = when (val purchase = state.purchase) {
        is PurchaseStatus.AwaitingPlay -> purchase.product == offer.product
        PurchaseStatus.Verifying -> true
        else -> false
    }
    HierroCard {
        HierroText(name.uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title)
        HierroText(price, style = HierroTheme.typography.title, color = HierroTheme.colors.accent)
        HierroText(renewalText(offer), color = HierroTheme.colors.textMuted)
        val label = stringResource(
            when {
                current -> R.string.settings_pro_current_plan
                switching -> R.string.settings_pro_switch
                else -> R.string.settings_pro_subscribe
            },
        )
        val description = stringResource(
            when {
                current -> R.string.settings_pro_current_plan_description
                switching -> R.string.settings_pro_switch_description
                else -> R.string.settings_pro_subscribe_description
            },
            *(if (current) arrayOf(name) else arrayOf(name, price)),
        )
        HierroButton(
            label,
            onClick = { actions.onSubscribe(offer.product) },
            Modifier.fillMaxWidth(),
            enabled = state.canTransact && !current,
            loading = inFlight,
            loadingLabel = stringResource(R.string.settings_pro_working),
            contentDescription = description,
        )
    }
}

@Composable
private fun Notice(state: ProUiState, actions: ProActions) {
    val message: String? = when (val restore = state.restore) {
        RestorePhase.Working -> stringResource(R.string.settings_pro_restore_working)
        RestorePhase.Restored -> stringResource(R.string.settings_pro_restore_done)
        RestorePhase.NothingToRestore -> stringResource(R.string.settings_pro_restore_nothing)
        RestorePhase.Pending -> stringResource(R.string.settings_pro_restore_pending)
        is RestorePhase.Failed -> stringResource(failureMessage(restore.reason))
        RestorePhase.Idle -> when (val purchase = state.purchase) {
            PurchaseStatus.Idle -> null
            is PurchaseStatus.AwaitingPlay -> stringResource(R.string.settings_pro_notice_awaiting)
            PurchaseStatus.Verifying -> stringResource(R.string.settings_pro_notice_verifying)
            PurchaseStatus.Pending -> stringResource(R.string.settings_pro_notice_pending)
            is PurchaseStatus.Completed -> stringResource(R.string.settings_pro_notice_completed)
            PurchaseStatus.Canceled -> stringResource(R.string.settings_pro_notice_canceled)
            is PurchaseStatus.Failed -> stringResource(failureMessage(purchase.reason))
        }
    }
    if (message == null) return
    val failed = state.purchase is PurchaseStatus.Failed || state.restore is RestorePhase.Failed
    val working = state.restore == RestorePhase.Working || state.purchase is PurchaseStatus.AwaitingPlay || state.purchase == PurchaseStatus.Verifying
    HierroCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        HierroText(message, color = if (failed) HierroTheme.colors.error else HierroTheme.colors.ink)
        if (!working) HierroButton(stringResource(R.string.settings_pro_notice_dismiss), actions.onDismissNotice, variant = HierroButtonVariant.Secondary)
    }
}

private fun failureMessage(reason: BillingFailure): Int = when (reason) {
    BillingFailure.PLAY_UNAVAILABLE -> R.string.settings_pro_fail_play
    BillingFailure.PRODUCT_UNAVAILABLE -> R.string.settings_pro_fail_product
    BillingFailure.NETWORK -> R.string.settings_pro_fail_network
    BillingFailure.VERIFICATION_UNAVAILABLE -> R.string.settings_pro_fail_verification
    BillingFailure.PURCHASE_INVALID -> R.string.settings_pro_fail_invalid
    BillingFailure.OTHER_ACCOUNT -> R.string.settings_pro_fail_other_account
    BillingFailure.ALREADY_BOUND -> R.string.settings_pro_fail_bound
    BillingFailure.SUBSCRIBED_ELSEWHERE -> R.string.settings_pro_fail_elsewhere
    BillingFailure.SIGNED_OUT -> R.string.settings_pro_fail_signed_out
    BillingFailure.UNKNOWN -> R.string.settings_pro_fail_unknown
}

private fun catalogMessage(reason: BillingFailure): Int = when (reason) {
    BillingFailure.PLAY_UNAVAILABLE -> R.string.settings_pro_unavailable_play
    BillingFailure.PRODUCT_UNAVAILABLE -> R.string.settings_pro_unavailable_product
    else -> R.string.settings_pro_unavailable_network
}

/** Play's price with its billing period in words: "4,99 € al mes" / "39,99 € al año". */
@Composable
internal fun priceText(offer: ProductOffer): String = when (offer.billingPeriod) {
    PERIOD_MONTH -> stringResource(R.string.settings_pro_price_month, offer.formattedPrice)
    PERIOD_YEAR -> stringResource(R.string.settings_pro_price_year, offer.formattedPrice)
    else -> stringResource(R.string.settings_pro_price_other, offer.formattedPrice)
}

@Composable
private fun renewalText(offer: ProductOffer): String = stringResource(
    when (offer.billingPeriod) {
        PERIOD_MONTH -> R.string.settings_pro_renewal_month
        PERIOD_YEAR -> R.string.settings_pro_renewal_year
        else -> R.string.settings_pro_renewal_other
    },
)

private const val PERIOD_MONTH = "P1M"
private const val PERIOD_YEAR = "P1Y"

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-ES"))

internal fun formatDate(millis: Long, zone: ZoneId): String = DATE_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))

@Composable
internal fun productName(product: ProProduct?): String = stringResource(
    when (product) {
        ProProduct.MONTHLY -> R.string.settings_sub_plan_monthly
        ProProduct.ANNUAL -> R.string.settings_sub_plan_annual
        null -> R.string.settings_sub_plan_unknown
    },
)

/** One plain sentence for the plan state; dates are the server's, shown in the device zone. */
@Composable
internal fun planSummary(plan: PlanStatus, zone: ZoneId): String = when (plan) {
    PlanStatus.Free -> stringResource(R.string.settings_sub_free)
    PlanStatus.Unverified -> stringResource(R.string.settings_sub_unverified)
    is PlanStatus.Active -> {
        val head = stringResource(R.string.settings_sub_active, productName(plan.product))
        plan.renewsAt?.let { head + ". " + stringResource(R.string.settings_sub_renews, formatDate(it, zone)) } ?: head
    }
    is PlanStatus.Grace -> plan.until?.let { stringResource(R.string.settings_sub_grace, formatDate(it, zone)) }
        ?: stringResource(R.string.settings_sub_grace_nodate)
    is PlanStatus.CanceledUntil -> plan.until?.let { stringResource(R.string.settings_sub_canceled, formatDate(it, zone)) }
        ?: stringResource(R.string.settings_sub_canceled_nodate)
    PlanStatus.Paused -> stringResource(R.string.settings_sub_paused)
    is PlanStatus.Ended -> plan.endedAt?.let { stringResource(R.string.settings_sub_ended, formatDate(it, zone)) }
        ?: stringResource(R.string.settings_sub_ended_nodate)
}
