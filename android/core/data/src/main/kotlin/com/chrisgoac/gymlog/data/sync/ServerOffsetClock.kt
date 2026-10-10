package com.chrisgoac.gymlog.data.sync

import com.chrisgoac.gymlog.domain.SyncClock
import com.chrisgoac.gymlog.network.ServerClock

/** [SyncClock] for local writes: device now + the offset to the server learned from response `Date` headers. */
class ServerOffsetClock(private val serverClock: ServerClock) : SyncClock {
    override fun nowMillis(): Long = serverClock.nowMillis()
}
