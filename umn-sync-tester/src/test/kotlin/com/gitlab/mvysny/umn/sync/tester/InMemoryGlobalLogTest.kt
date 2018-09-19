package com.gitlab.mvysny.umn.sync.tester

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.sync.InMemoryGlobalLog

class InMemoryGlobalLogTest : DynaTest({
    lateinit var log: InMemoryGlobalLog
    beforeEach { log = InMemoryGlobalLog(JavaDocumentSerializer, AutoTypeResolver) }

    globalLogClientTestBattery { log.newClient() }
})
