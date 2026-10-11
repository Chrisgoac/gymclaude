package com.chrisgoac.gymlog.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.billing.BillingFailure
import com.chrisgoac.gymlog.domain.billing.BillingRepository
import com.chrisgoac.gymlog.domain.billing.Entitlement
import com.chrisgoac.gymlog.domain.billing.ProProduct
import com.chrisgoac.gymlog.domain.billing.ProductCatalog
import com.chrisgoac.gymlog.domain.billing.ProductOffer
import com.chrisgoac.gymlog.domain.billing.PurchaseStatus
import com.chrisgoac.gymlog.domain.billing.RestoreResult
import com.chrisgoac.gymlog.domain.billing.SubscriptionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the account's subscription looks like to the user. Derived only from the server-backed [Entitlement]:
 * the Play client never grants anything. Everything but the three AI entry points stays free in every case.
 */
sealed interface PlanStatus {
    /** No subscription (or none known). */
    data object Free : PlanStatus

    /** Nothing trustworthy is known yet (never verified, or the offline cache ran out). */
    data object Unverified : PlanStatus

    /** Pro, renewing on [renewsAt] (server epoch ms, null when unknown). */
    data class Active(val product: ProProduct?, val renewsAt: Long?) : PlanStatus

    /** Pro until [until] although the last payment failed: Play is retrying, the user fixes it in Play. */
    data class Grace(val until: Long?) : PlanStatus

    /** Canceled but paid: Pro until [until], then it will not renew. */
    data class CanceledUntil(val product: ProProduct?, val until: Long?) : PlanStatus

    /** On hold or paused in Play: no Pro until it is resumed or fixed there. */
    data object Paused : PlanStatus

    /** The subscription ended on [endedAt] (null when unknown). */
    data class Ended(val endedAt: Long?) : PlanStatus

    val isPro: Boolean get() = this is Active || this is Grace || this is CanceledUntil

    /** A Play subscription exists in some form, so "Gestionar en Google Play" makes sense. */
    val hasSubscription: Boolean get() = this !is Free && this !is Unverified

    companion object {
        fun of(entitlement: Entitlement): PlanStatus = when {
            entitlement.isPro -> when (entitlement.state) {
                SubscriptionState.GRACE -> Grace(entitlement.validUntil)
                SubscriptionState.CANCELED -> CanceledUntil(entitlement.product, entitlement.validUntil)
                else -> Active(entitlement.product, entitlement.validUntil)
            }
            else -> when (entitlement.state) {
                SubscriptionState.PAUSED -> Paused
                SubscriptionState.EXPIRED, SubscriptionState.CANCELED -> Ended(entitlement.validUntil)
                SubscriptionState.UNKNOWN -> Unverified
                else -> Free
            }
        }
    }
}

/** The Restore button's outcome, shown until the user acts again. */
sealed interface RestorePhase {
    data object Idle : RestorePhase
    data object Working : RestorePhase
    data object Restored : RestorePhase
    data object NothingToRestore : RestorePhase
    data object Pending : RestorePhase
    data class Failed(val reason: BillingFailure) : RestorePhase
}

data class ProUiState(
    val entitlement: Entitlement = Entitlement.Unknown,
    val catalog: ProductCatalog = ProductCatalog.Loading,
    /** The purchase the user started; [PurchaseStatus.Idle] once it was dismissed or none was started here. */
    val purchase: PurchaseStatus = PurchaseStatus.Idle,
    val restore: RestorePhase = RestorePhase.Idle,
    val online: Boolean = true,
) {
    val plan: PlanStatus get() = PlanStatus.of(entitlement)

    /** A purchase sheet, verification or restore is in flight: no second one may start. */
    val busy: Boolean
        get() = purchase is PurchaseStatus.AwaitingPlay || purchase is PurchaseStatus.Verifying || restore is RestorePhase.Working

    /** Purchase and restore need the network (Play and the server) and no other one in flight. */
    val canTransact: Boolean get() = online && !busy

    fun offer(product: ProProduct): ProductOffer? =
        (catalog as? ProductCatalog.Available)?.offers?.firstOrNull { it.product == product }
}

/**
 * The Pro screen: Play's localized offers, the purchase / restore actions and the entitlement the server
 * granted. State lives here so a purchase in flight survives rotation; the Activity is only handed to
 * [BillingRepository.launchPurchase] for the call and never kept.
 */
class ProViewModel(
    private val billing: BillingRepository,
    online: Flow<Boolean>,
) : ViewModel() {
    private val restore = MutableStateFlow<RestorePhase>(RestorePhase.Idle)

    /** A finished purchase status the user already saw (or that predates this screen): hidden, not repeated. */
    private val ignoredPurchase = MutableStateFlow<PurchaseStatus?>(billing.purchaseStatus.value.takeIf(::isTerminal))

    val state: StateFlow<ProUiState> = combine(
        billing.entitlement,
        billing.products,
        combine(billing.purchaseStatus, ignoredPurchase) { status, ignored -> if (status == ignored) PurchaseStatus.Idle else status },
        restore,
        online.catch { emit(true) },
    ) { entitlement, catalog, purchase, restorePhase, isOnline ->
        ProUiState(entitlement, catalog, purchase, restorePhase, isOnline)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ProUiState())

    init {
        viewModelScope.launch {
            billing.loadProducts()
            billing.refresh()
        }
    }

    fun reloadProducts() {
        viewModelScope.launch { billing.loadProducts() }
    }

    /** Starts the Play purchase (or plan switch) for [product]; [activity] is the foreground `android.app.Activity`. */
    fun purchase(activity: Any, product: ProProduct) {
        if (!state.value.canTransact) return
        ignoredPurchase.value = null
        restore.value = RestorePhase.Idle
        viewModelScope.launch {
            try {
                billing.launchPurchase(activity, product)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The repository reports its own failures through purchaseStatus; nothing to add.
            }
        }
    }

    fun restorePurchases() {
        if (!state.value.canTransact) return
        ignoredPurchase.value = billing.purchaseStatus.value.takeIf(::isTerminal)
        restore.value = RestorePhase.Working
        viewModelScope.launch {
            val outcome = try {
                billing.restore()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RestoreResult.Failed(BillingFailure.UNKNOWN)
            }
            restore.update {
                when (outcome) {
                    is RestoreResult.Restored -> RestorePhase.Restored
                    RestoreResult.NothingToRestore -> RestorePhase.NothingToRestore
                    RestoreResult.Pending -> RestorePhase.Pending
                    is RestoreResult.Failed -> RestorePhase.Failed(outcome.reason)
                }
            }
        }
    }

    /** Hides the purchase / restore message (the user read it). */
    fun dismissNotice() {
        ignoredPurchase.value = billing.purchaseStatus.value.takeIf(::isTerminal)
        if (restore.value !is RestorePhase.Working) restore.value = RestorePhase.Idle
    }

    /** Play's subscription management page for the current plan. */
    fun managementUri(): String = billing.managementUri()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        fun isTerminal(status: PurchaseStatus): Boolean = when (status) {
            PurchaseStatus.Canceled, is PurchaseStatus.Failed, is PurchaseStatus.Completed -> true
            else -> false
        }
    }
}
