package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner

/** Tells [onCommitted] after every local mutation that committed; failures propagate untouched. */
class NotifyingLocalMutationRunner(
    private val delegate: LocalMutationRunner,
    private val onCommitted: () -> Unit,
) : LocalMutationRunner {
    override suspend fun <T> run(mutation: LocalMutation<T>): T = delegate.run(mutation).also { onCommitted() }
}
