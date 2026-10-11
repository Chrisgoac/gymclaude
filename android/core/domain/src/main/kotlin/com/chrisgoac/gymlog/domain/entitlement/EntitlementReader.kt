package com.chrisgoac.gymlog.domain.entitlement

import kotlinx.coroutines.flow.Flow

/**
 * Whether the signed-in account holds the Pro entitlement. Features only read it to enable or lock
 * Pro actions (such as the intelligent report); the server stays the authority. Implemented in `app`
 * (Plan 5 wires Billing); features never import Billing.
 */
interface EntitlementReader {
    /** Current Pro state; re-emits when it changes. */
    fun observeIsPro(): Flow<Boolean>
}
