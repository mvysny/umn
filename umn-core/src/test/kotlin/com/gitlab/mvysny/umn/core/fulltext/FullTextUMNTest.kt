package com.gitlab.mvysny.umn.core.fulltext

import com.gitlab.mvysny.umn.core.UMN
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.core.createMvstoreUMN
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.expect

class FullTextUMNTest {
    @Nested inner class FullText : FullTextTestBattery() {
        override fun newUmn(tokens: (Any) -> Set<String>): UMN = FullTextUMN(createMvstoreUMN(), tokens)
        override fun search(umn: UMN, tokens: Set<String>): Sequence<UUID> = (umn as FullTextUMN).search(tokens)
    }
    @Nested inner class FullText2 : FullTextTestBattery() {
        override fun newUmn(tokens: (Any) -> Set<String>): UMN = FullText2UMN(createMvstoreUMN(), tokens)
        override fun search(umn: UMN, tokens: Set<String>): Sequence<UUID> = (umn as FullText2UMN).search(tokens)
    }
}

/**
 * Tests a full-text [UMN] over String documents; run it like [com.gitlab.mvysny.umn.core.GenericUmnTestBattery].
 */
abstract class FullTextTestBattery {
    /**
     * Returns a new empty full-text storage which indexes documents by [tokens].
     */
    abstract fun newUmn(tokens: (Any) -> Set<String>): UMN

    /**
     * Searches [umn] created by [newUmn] for documents matching all [tokens].
     */
    abstract fun search(umn: UMN, tokens: Set<String>): Sequence<UUID>

    private lateinit var umn: UMN
    private val analyzer: Analyzer = JavaSimpleAnalyzer()
    @BeforeEach fun createUmn() {
        umn = newUmn { analyzer.getTokens(it as String) }
    }
    @AfterEach fun closeUmn() {
        umn.closeQuietly()
    }

    private fun query(text: String): List<String> = search(umn, analyzer.getTokens(text)).map { umn.get(it, String::class) } .toList()

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

    @Test fun `create with given ID`() {
        val id = UUID.randomUUID()
        expect(true) { umn.create("aa b", id) }
        expect(false) { umn.create("c", id) }
        expect(listOf("aa b")) { query("b") }
        expect(listOf()) { query("c") }
    }

    @Test fun `update re-indexes the document`() {
        val id = umn.create("aa b")
        umn.update(id, "aa cc")
        expect(listOf("aa cc")) { query("aa") }
        expect(listOf("aa cc")) { query("cc") }
        expect(listOf()) { query("b") }
    }

    @Test fun `delete after update removes the document from the index`() {
        val id = umn.create("aa b")
        umn.update(id, "aa cc")
        umn.delete(id)
        expect(listOf()) { query("aa") }
        expect(listOf()) { query("cc") }
    }

    @Test fun `delete removes the document from the index`() {
        val id = umn.create("aa b")
        umn.create("aa")
        umn.delete(id)
        expect(listOf("aa")) { query("aa") }
        expect(listOf()) { query("b") }
    }
}
