package com.gitlab.mvysny.umn.core

import com.github.mvysny.dynatest.DynaNodeGroup
import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectThrows
import java.util.*

/**
 * A generic test battery to test basic properties of a UMN storage.
 */
fun DynaNodeGroup.genericUmnTestBattery(emptyStorageProvider: ()->UMN) {
    lateinit var umn: UMN
    beforeEach { umn = emptyStorageProvider() }
    afterEach { umn.close() }

    group("update") {
        test("update of non-existing document should fail") {
            expectThrows(IllegalArgumentException::class) {
                umn.update(UUID.randomUUID(), "foo", String::class)
            }
        }
    }
}

/**
 * Tests the [InMemoryUMN] implementation.
 */
class InMemoryUMNTest : DynaTest({
    genericUmnTestBattery { InMemoryUMN() }
})
