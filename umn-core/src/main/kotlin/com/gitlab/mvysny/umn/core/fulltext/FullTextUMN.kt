package com.gitlab.mvysny.umn.core.fulltext

import com.gitlab.mvysny.umn.core.UMN
import com.gitlab.mvysny.umn.core.UtilityMap
import java.util.*

/**
 * @param tokens produces tokens for a document, see [Analyzer.getTokens] for more info.
 */
class FullTextUMN(override val delegate: UMN, private val tokens: (Any) -> Set<String>) : UMN by delegate {
    private fun getTokenMap(token: String): UtilityMap<UUID, Boolean> = delegate.openUtilityMap("_FT_$token")
    override fun create(document: Any): UUID {
        val id = delegate.create(document)
        tokens(document).forEach { token -> getTokenMap(token).put(id, true) }
        return id
    }

    override fun update(id: UUID, document: Any) {
        val oldDoc: Any = get(id, Any::class)
        val oldTokens = tokens(oldDoc)
        val newTokens = tokens(document)
        delegate.update(id, document)
        (oldTokens - newTokens).forEach { token -> getTokenMap(token).put(id, null) }
        (newTokens - oldTokens).forEach { token -> getTokenMap(token).put(id, true) }
    }

    override fun delete(id: UUID) {
        val oldDoc: Any = get(id, Any::class)
        val oldTokens = tokens(oldDoc)
        delegate.delete(id)
        oldTokens.forEach { token -> getTokenMap(token).put(id, null) }
    }

    override fun create(document: Any, id: UUID): Boolean {
        if (delegate.create(document, id)) {
            tokens(document).forEach { token -> getTokenMap(token).put(id, true) }
            return true
        }
        return false
    }

    fun search(tokens: Set<String>): Sequence<UUID> {
        val sequences = tokens.map { token -> getTokenMap(token).keyIterator() }
        // todo intersect sequences? or lookup map keys?
        return emptySequence()
    }
}
