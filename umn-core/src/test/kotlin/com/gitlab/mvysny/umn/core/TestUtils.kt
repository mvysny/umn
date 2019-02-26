package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVStore

/**
 * Creates an [UMN] backed by a file-based MV Store.
 */
fun createMvstoreUMN(dbFileName: String = createTempFile().absolutePath): MvstoreUMN = MvstoreUMN(MVStore.open(dbFileName), JavaDocumentSerializer,
    AutoTypeResolver)
