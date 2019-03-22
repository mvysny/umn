package com.gitlab.mvysny.umn.core.fulltext

import com.github.mvysny.dynatest.DynaTest
import kotlin.test.expect

class JavaSimpleAnalyzerTest : DynaTest({
    lateinit var analyzer: Analyzer
    beforeEach { analyzer = JavaSimpleAnalyzer() }
    group("getTokens") {
        test("jp") {
            expect(setOf("私", "は", "彼女", "をかわいらしいと", "思", "った")) { analyzer.getTokens("私は彼女をかわいらしいと思った。") }
        }
        test("en") {
            expect(setOf("list", "fly", "pig")) { analyzer.getTokens("the list of flying pigs") }
        }
        test("sk-normalize") {
            expect(setOf("e", "i", "o", "u")) { analyzer.getTokens("Ǎ ǎ Ě ě Ǐ ǐ Ǒ ǒ Ǔ ǔ") }
        }
    }
})
