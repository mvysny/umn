package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVStore
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.expect

class MvstoreUMNTest {
    @Nested inner class InMemoryTests : GenericUmnTestBattery() {
        override fun newUmn(): UMN = MvstoreUMN(MVStore.open(null), JavaDocumentSerializer, AutoTypeResolver)
    }

    @Nested inner class FileBasedTests : GenericUmnTestBattery() {
        override fun newUmn(): UMN = createMvstoreUMN()
    }

    @Test fun `threads opening a new utility map at once all write into one map`() {
        MvstoreUMN(MVStore.open(null), JavaDocumentSerializer, AutoTypeResolver).use { umn ->
            repeat(20) { round ->
                val barrier = CyclicBarrier(8)
                List(8) { i ->
                    thread {
                        barrier.await()
                        umn.openUtilityMap<Int, String>("race$round").put(i, "foo")
                    }
                }.forEach { it.join() }
                expect(8, "round $round") { umn.openUtilityMap<Int, String>("race$round").keyCount() }
            }
        }
    }
}
