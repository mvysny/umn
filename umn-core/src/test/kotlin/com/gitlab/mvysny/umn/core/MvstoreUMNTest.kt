package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaTest
import org.h2.mvstore.MVStore
import java.io.File

class MvstoreUMNTest : DynaTest({
    group("in-memory tests") {
        genericUmnTestBattery { MvstoreUMN(MVStore.open(null), JavaDocumentSerializer) }
    }

    group("file-based tests") {
        genericUmnTestBattery {
            val db: File = createTempFile()
            MvstoreUMN(MVStore.open(db.absolutePath), JavaDocumentSerializer)
        }
    }
})
