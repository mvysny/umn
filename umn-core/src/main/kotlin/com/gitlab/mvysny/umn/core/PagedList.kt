package com.gitlab.mvysny.umn.core

/**
 * A read-only list of [size] items that [loadPage] loads [pageSize] at a time; see [IndexingUMN.IndexImpl.keyPagedList].
 * Keeps the page read last and its two neighbours.
 *
 * @param loadPage loads the items at the given positions; fewer when the source has shrunk since.
 * @param dataMissingMarker read in place of an item [loadPage] no longer returns; null throws [IndexOutOfBoundsException].
 */
internal class PagedList<T : Any>(
    override val size: Int,
    private val pageSize: Int,
    private val dataMissingMarker: T?,
    private val loadPage: (IntRange) -> List<T>
) : AbstractList<T>() {
    private val pages = mutableMapOf<Int, List<T>>()

    init {
        require(pageSize >= 1) { "pageSize must be 1 or greater: $pageSize" }
        require(size >= 0) { "size must be 0 or greater: $size" }
    }

    override fun get(index: Int): T {
        if (index !in indices) throw IndexOutOfBoundsException("$index is not in 0..<$size")
        val pageIndex = index / pageSize
        pages.keys.retainAll(setOf(pageIndex - 1, pageIndex, pageIndex + 1))
        val page = pages.getOrPut(pageIndex) {
            val start = pageIndex * pageSize
            loadPage(start..minOf(size - 1L, start + pageSize - 1L).toInt())
        }
        return page.getOrNull(index % pageSize) ?: dataMissingMarker
            ?: throw IndexOutOfBoundsException("$index: the source has shrunk since this list was created")
    }
}
