package com.gitlab.mvysny.umn.sync.tester

import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectThrows
import kotlin.test.expect

class ContainerIdTest : DynaTest({
    test("valid id") {
        expect("2803125f466f1c735651257c2410ddd05d6d315ed49568a05d19174883f959b0") {
            ContainerId("2803125f466f1c735651257c2410ddd05d6d315ed49568a05d19174883f959b0").id
        }
    }

    group("invalid id") {
        test("empty id") {
            expectThrows(IllegalArgumentException::class) {
                ContainerId("")
            }
        }
        test("too short id") {
            expectThrows(IllegalArgumentException::class) {
                ContainerId("foo")
            }
        }
        test("id not a hex number") {
            expectThrows(IllegalArgumentException::class) {
                ContainerId("kvak125f466f1c735651257c2410ddd05d6d315ed49568a05d19174883f959b0")
            }
        }
    }
})
