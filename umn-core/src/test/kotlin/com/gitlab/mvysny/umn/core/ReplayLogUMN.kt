package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaTest
import org.h2.mvstore.MVStore

/**
 * Tests the [IndexingUMN] class.
 */
class ReplayLogUMNTest : DynaTest({
    // doesn't test the indexing capabilities per se; it tests whether the IndexingUMN still behaves as a proper UMN storage.
    group("basic tests") {
        group("in-memory") {
            genericUmnTestBattery { ReplayLogUMN(InMemoryUMN(), JavaDocumentSerializer) }
        }
        group("on mvstore") {
            genericUmnTestBattery {
                val mvstore = MvstoreUMN(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer)
                ReplayLogUMN(mvstore, JavaDocumentSerializer)
            }
        }
    }
})
