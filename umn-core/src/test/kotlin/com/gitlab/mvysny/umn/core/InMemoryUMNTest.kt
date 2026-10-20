package com.gitlab.mvysny.umn.core

/**
 * Tests the [InMemoryUMN] implementation.
 */
class InMemoryUMNTest : GenericUmnTestBattery() {
    override fun newUmn(): UMN = InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer)
}
