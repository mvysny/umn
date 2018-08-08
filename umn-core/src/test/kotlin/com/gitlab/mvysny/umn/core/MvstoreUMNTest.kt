package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaTest
import org.h2.mvstore.MVStore

class MvstoreUMNTest : DynaTest({
    group("in-memory tests") {
        genericUmnTestBattery { MvstoreUMN(MVStore.open(null), JavaDocumentSerializer, AutoTypeResolver) }
    }

    group("file-based tests") {
        genericUmnTestBattery { createMvstoreUMN() }
    }
})
