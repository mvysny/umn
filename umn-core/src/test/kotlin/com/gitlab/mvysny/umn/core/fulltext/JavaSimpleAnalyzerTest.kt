package com.gitlab.mvysny.umn.core.fulltext

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.expect

class JavaSimpleAnalyzerTest {
    private lateinit var analyzer: Analyzer
    @BeforeEach fun createAnalyzer() { analyzer = JavaSimpleAnalyzer() }
    @Nested inner class GetTokens {
        @Test fun `jp`() {
            expect(setOf("私", "は", "彼女", "をかわいらしいと", "思", "った")) { analyzer.getTokens("私は彼女をかわいらしいと思った。") }
        }
        @Test fun `en`() {
            expect(setOf("list", "fly", "pig")) { analyzer.getTokens("the list of flying pigs") }
        }
        @Test fun `sk-normalize`() {
            expect(setOf("e", "i", "o", "u")) { analyzer.getTokens("Ǎ ǎ Ě ě Ǐ ǐ Ǒ ǒ Ǔ ǔ") }
        }
    }
}
