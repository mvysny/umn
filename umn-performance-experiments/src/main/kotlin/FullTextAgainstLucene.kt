@file:Suppress("DEPRECATION")

package com.gitlab.mvysny.umn.core.fulltext

import com.gitlab.mvysny.umn.core.MvstoreUMN
import com.gitlab.mvysny.umn.core.facet
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryParser.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.Version
import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import java.io.File
import java.util.*

fun withFullTextUMN(block: (umn: FullTextUMN)->Unit) {
    val analyzer = JavaSimpleAnalyzer()
    FullTextUMN(createMvstoreUMN(umnFile.absolutePath)) { analyzer.getTokens(it as String) } .use {
        block(it)
        it.facet<MvstoreUMN>().compact()
    }
}

fun withFullText2UMN(block: (umn: FullText2UMN)->Unit) {
    val analyzer = JavaSimpleAnalyzer()
    FullText2UMN(createMvstoreUMN(umnFile.absolutePath)) { analyzer.getTokens(it as String) } .use {
        block(it)
        it.facet<MvstoreUMN>().compact()
    }
}

fun FullTextUMN.query(text: String, limit: Int = Integer.MAX_VALUE): List<String> {
    val analyzer = JavaSimpleAnalyzer()
    return search(analyzer.getTokens(text)).map { get(it, String::class) } .take(limit).toList()
}

fun FullText2UMN.query(text: String, limit: Int = Integer.MAX_VALUE): List<String> {
    val analyzer = JavaSimpleAnalyzer()
    return search(analyzer.getTokens(text)).map { get(it, String::class) } .take(limit).toList()
}

fun main() {
    val analyzer = JavaSimpleAnalyzer()
    val cityNames = File("/home/mavi/Downloads/city.list.json").useLines { lines ->
        lines.filter { it.contains("\"name\": \"") } .map { it.trim().removePrefix("\"name\": \"").removeSuffix("\",") } .toList()
    }
    val wordCount = mutableMapOf<String, Int>()
    cityNames.forEach { analyzer.getTokens(it).forEach { wordCount.compute(it) { _, cnt -> if (cnt == null) 1 else cnt + 1 } } }
    println(wordCount.toList().sortedBy { it.second } .reversed().take(10))
    println("Loaded ${cityNames.size} cities, total chars: ${cityNames.sumBy { it.length }}")

    time("MVStore direct API") {
        val temp = createTempFile()
        val r = Random()
        val mvStore: MVStore = MVStore.Builder().compress().fileName(temp.absolutePath).open()
        try {
//            val map = mvStore.openMap("RAW", MVMap.Builder<Long, ByteArray>().valueType(ByteArrayType()))
            val map = mvStore.openMap("RAW", MVMap.Builder<Long, ByteArray>())
            (0..210000).forEach { map.put(r.nextLong(), it.toString().toByteArray()) }
            time("compacting") {
                mvStore.compactRewriteFully()
                mvStore.compactMoveChunks()
            }
        } finally {
            mvStore.close()
        }
        println("MVStore: $temp - ${temp.length()} bytes")
    }

    time("simple umn create") {
        val temp = createTempFile()
        createMvstoreUMN(temp.absolutePath).use { umn ->
            cityNames.forEach { umn.create(it) }
            umn.compact()
        }
        println("UMN: $temp - ${temp.length()} bytes")
    }

    withFullTextUMN { umn ->
        time("create") {
            cityNames.forEach { umn.create(it) }
        }
    }
    println("UMN produced $umnFile - ${umnFile.length()} bytes")

    withFullTextUMN { umn ->
        Thread.sleep(1000)
        time("100 queries") {
            repeat(100) {
                umn.query("State", 30)
            }
        }
        Thread.sleep(1000)
        time("10000 queries") {
            println(umn.query("de la san", 200))
            repeat(10000) {
                val query: List<String> = umn.query("de la san", 30)
                require(query.size == 30) { "$query" }
            }
        }
    }

    withFullText2UMN { umn ->
        time("create 2") {
            cityNames.forEach { umn.create(it) }
        }
    }
    println("UMN2 produced $umnFile - ${umnFile.length()} bytes")

    withFullText2UMN { umn ->
        Thread.sleep(1000)
        time("100 queries 2") {
            repeat(100) {
                umn.query("State", 30)
            }
        }
        Thread.sleep(1000)
        time("10000 queries 2") {
            println(umn.query("de la san", 200))
            repeat(10000) {
                val query: List<String> = umn.query("de la san", 30)
                require(query.size == 30) { "$query" }
            }
        }
    }

    time("lucene create") {
        withLucene { luceneWriter ->
            cityNames.forEach {
                val doc = Document()
                doc.add(Field("contents", it, Field.Store.YES, Field.Index.ANALYZED))
                luceneWriter.addDocument(doc)
            }
        }
    }
    println("Lucene produced $luceneDir - ${luceneDir.calculateDirSize()} bytes")

    withLucSearch { searcher, parser ->
        Thread.sleep(1000)
        time("100 queries") {
            repeat(100) {
                val docs = searcher.search(parser.parse("State"), null, 30).scoreDocs.map { searcher.doc(it.doc) }
                require(docs.size == 30)
            }
        }
    }
    Thread.sleep(1000)

    withLucSearch { searcher, parser ->
        time("10000 queries") {
            repeat(10000) {
                val docs = searcher.search(parser.parse("de la san"), null, 30).scoreDocs.map { searcher.doc(it.doc) }
                require(docs.size == 30)
            }
        }
    }
}

private val luceneDir = createTempDir()
private val umnFile = createTempFile()

fun withLucene(block: (luceneWriter: IndexWriter)->Unit) {
    FSDirectory.open(luceneDir).use { directory ->
        StandardAnalyzer(Version.LUCENE_30).use { analyzer ->
            IndexWriter(directory, IndexWriterConfig(Version.LUCENE_30, analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE)).use { luceneWriter ->
                luceneWriter.maxFieldLength = IndexWriter.MaxFieldLength.UNLIMITED.limit
                block(luceneWriter)
                println("Optimizing Lucene index")
                luceneWriter.forceMerge(1, true)
            }
        }
    }
}

private fun withLucSearch(block: (IndexSearcher, QueryParser)->Unit) {
    FSDirectory.open(luceneDir).use { directory ->
        IndexReader.open(directory, true).use { reader ->
            IndexSearcher(reader).use { searcher ->
                val parser = QueryParser(Version.LUCENE_30, "contents", StandardAnalyzer(Version.LUCENE_30))
                block(searcher, parser)
            }
        }
    }
}
