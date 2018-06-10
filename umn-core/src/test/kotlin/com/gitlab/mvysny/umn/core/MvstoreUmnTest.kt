package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaTest
import org.h2.mvstore.MVStore

class MvstoreUmnTest : DynaTest({
    genericUmnTestBattery { MvstoreUmn(MVStore.open(null), JavaDocumentSerializer) }
})
