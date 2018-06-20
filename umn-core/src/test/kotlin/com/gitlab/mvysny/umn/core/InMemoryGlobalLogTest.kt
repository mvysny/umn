package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaTest

class InMemoryGlobalLogTest : DynaTest({
    lateinit var log: InMemoryGlobalLog
    beforeEach { log = InMemoryGlobalLog() }

    globalLogClientTestBattery { log.newClient() }

    // run the basic test battery on the replicating UMN
    group("basic test battery on replicating UMN") {
        genericUmnTestBattery { InMemoryUMN().syncing(log.newClient()) }
    }
})