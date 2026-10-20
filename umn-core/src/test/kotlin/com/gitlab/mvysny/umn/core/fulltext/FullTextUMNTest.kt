package com.gitlab.mvysny.umn.core.fulltext

import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.core.createMvstoreUMN
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.expect

class FullTextUMNTest {
    private lateinit var umn: FullTextUMN
    private lateinit var analyzer: Analyzer
    @BeforeEach fun createUmn() {
        analyzer = JavaSimpleAnalyzer()
        umn = FullTextUMN(createMvstoreUMN()) { analyzer.getTokens(it as String) }
    }
    @AfterEach fun closeUmn() {
        umn.closeQuietly()
    }

    private fun query(text: String): List<String> = umn.search(analyzer.getTokens(text)).map { umn.get(it, String::class) } .toList()

    @Test fun `empty`() {
        expect(listOf()) { query("foo") }
        expect(listOf()) { query("") }
        expect(listOf()) { query("a b c") }
    }

    @Test fun `simple search`() {
        listOf("aa", "aa b", "aa b c").forEach { umn.create(it) }
        expect(listOf()) { query("foo") }
        expect(listOf()) { query("") }
        expect(listOf("aa b c")) { query("c") }
        expect(setOf("aa", "aa b", "aa b c")) { query("aa").toSet() }
        expect(listOf()) { query("a") }
        expect(listOf()) { query("bb") }
    }

    @Test fun `hashjoin search`() {
        listOf("aa", "aa b", "aa b c").forEach { umn.create(it) }
        expect(setOf("aa b c", "aa b")) { query("aa b").toSet() }
        expect(setOf("aa b c", "aa b")) { query("b aa").toSet() }
        expect(setOf("aa b c")) { query("b c").toSet() }
        expect(listOf()) { query("b c d") }
    }

    @Test fun `mergejoin search`() {
        // this one is triggered when there is more than 10 matching documents, and we search by 2 or more tokens
        (0..1000).forEach { umn.create("${it / 10} ${(it / 10) + 1}") }

        expect(setOf("10 11", "11 12")) { query("11").toSet() }
        val result = query("11 10")
        expect(setOf("10 11"), result.toString()) { result.toSet() }
        expect(10, result.toString()) { result.size }
    }
}
