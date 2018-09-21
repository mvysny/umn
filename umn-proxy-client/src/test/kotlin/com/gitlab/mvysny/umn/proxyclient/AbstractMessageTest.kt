package com.gitlab.mvysny.umn.proxyclient

import com.github.mvysny.dynatest.DynaTest
import kotlin.test.expect

class AbstractMessageTest : DynaTest({
    test("toString") {
        expect("Started") { AbstractMessage.Started.toString() }
    }
})
