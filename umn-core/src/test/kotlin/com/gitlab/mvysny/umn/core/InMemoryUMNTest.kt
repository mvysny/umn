package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaTest

/**
 * Tests the [InMemoryUMN] implementation.
 */
class InMemoryUMNTest : DynaTest({
    genericUmnTestBattery { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer) }
})
