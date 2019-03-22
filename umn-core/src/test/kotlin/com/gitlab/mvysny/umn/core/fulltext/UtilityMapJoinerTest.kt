package com.gitlab.mvysny.umn.core.fulltext

import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList

class UtilityMapJoinerTest : DynaTest({
    group("mergejoin") {
        test("ranges") {
            expectList(5) { mergeJoin(listOf((0..5).iterator(), (5..10).iterator())).toList() }
            expectList(5) { mergeJoin(listOf((0..5).iterator(), (5..10).iterator(), (5..7).iterator())).toList() }
            expectList(0, 1, 2, 3, 4, 5) { mergeJoin(listOf((0..5).iterator(), (0..10).iterator())).toList() }
            expectList() { mergeJoin(listOf((0..5).iterator(), (6..10).iterator())).toList() }
            expectList(6, 7, 8) { mergeJoin(listOf((0..10).iterator(), (6..8).iterator())).toList() }
        }

        test("disjoint lists") {
            expectList() { mergeJoin(listOf(listOf(0, 2, 4, 6).iterator(), listOf(1, 3, 5, 7).iterator())).toList() }
        }
    }
})
