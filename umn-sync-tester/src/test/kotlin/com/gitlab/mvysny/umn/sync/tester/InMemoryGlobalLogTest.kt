package com.gitlab.mvysny.umn.sync.tester

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.InMemoryUMN
import com.gitlab.mvysny.umn.sync.InMemoryGlobalLog
import com.gitlab.mvysny.umn.sync.syncing

class InMemoryGlobalLogTest : DynaTest({
    lateinit var log: InMemoryGlobalLog
    beforeEach { log = InMemoryGlobalLog() }

    globalLogClientTestBattery { log.newClient() }
})
