package com.gitlab.mvysny.umn.sync

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.InMemoryUMN
import com.gitlab.mvysny.umn.core.genericUmnTestBattery

class InMemorySyncingUMNTest : DynaTest({
    lateinit var log: InMemoryGlobalLog
    beforeEach { log = InMemoryGlobalLog() }

    // run the basic test battery on the replicating UMN
    group("basic test battery on replicating UMN") {
        genericUmnTestBattery { InMemoryUMN().syncing(log.newClient()) }
    }
})