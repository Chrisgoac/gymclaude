package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.RootState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/** Inputs of the achievement reconciliation scheduler derived from the root state. */
object AchievementTriggers {
    /** One signal each time an account becomes [RootState.Ready] (bootstrap done). */
    fun readyAccounts(rootState: Flow<RootState>): Flow<Unit> =
        rootState.map { (it as? RootState.Ready)?.userId }.distinctUntilChanged().filterNotNull().map { }

    /** Reconciling is allowed only with a Ready account (never against a partially bootstrapped database) and no workout being recorded. */
    fun canRun(rootState: Flow<RootState>, workoutActive: Flow<Boolean>): Flow<Boolean> =
        combine(rootState.map { it is RootState.Ready }, workoutActive) { ready, active -> ready && !active }.distinctUntilChanged()
}
