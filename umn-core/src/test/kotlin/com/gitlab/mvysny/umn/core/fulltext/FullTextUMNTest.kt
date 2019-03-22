package com.gitlab.mvysny.umn.core.fulltext

import com.github.mvysny.dynatest.DynaTest
import com.github.mvysny.dynatest.expectList
import com.gitlab.mvysny.umn.core.closeQuietly
import com.gitlab.mvysny.umn.core.createMvstoreUMN
import kotlin.test.expect

class FullTextUMNTest : DynaTest({
    lateinit var umn: FullTextUMN
    lateinit var analyzer: Analyzer
    beforeEach {
        analyzer = JavaSimpleAnalyzer()
        umn = FullTextUMN(createMvstoreUMN()) { analyzer.getTokens(it as String) }
    }
    afterEach {
        umn.closeQuietly()
    }

    fun query(text: String): List<String> = umn.search(analyzer.getTokens(text)).map { umn.get(it, String::class) } .toList()

    test("empty") {
        expectList() { query("foo") }
        expectList() { query("") }
        expectList() { query("a b c") }
    }

    test("simple search") {
        listOf("aa", "aa b", "aa b c").forEach { umn.create(it) }
        expectList() { query("foo") }
        expectList() { query("") }
        expectList("aa b c") { query("c") }
        expect(setOf("aa", "aa b", "aa b c")) { query("aa").toSet() }
        expectList() { query("a") }
        expectList() { query("bb") }
    }

    test("hashjoin search") {
        listOf("aa", "aa b", "aa b c").forEach { umn.create(it) }
        expect(setOf("aa b c", "aa b")) { query("aa b").toSet() }
        expect(setOf("aa b c", "aa b")) { query("b aa").toSet() }
        expect(setOf("aa b c")) { query("b c").toSet() }
        expectList() { query("b c d") }
    }

    test("mergejoin search") {
        // this one is triggered when there is more than 10 matching documents, and we search by 2 or more tokens
        (0..1000).forEach { umn.create("${it / 10} ${(it / 10) + 1}") }

        expect(setOf("10 11", "11 12")) { query("11").toSet() }
        val result = query("11 10")
        expect(setOf("10 11"), result.toString()) { result.toSet() }
        expect(10, result.toString()) { result.size }
    }
})
