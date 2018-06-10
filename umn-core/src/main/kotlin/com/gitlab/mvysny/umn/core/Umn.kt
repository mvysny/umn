package com.gitlab.mvysny.umn.core

import java.util.*

interface Umn {
    fun create(doc: ByteArray): UUID
    fun update(id: UUID, doc: ByteArray)
    fun delete(id: UUID)
}
