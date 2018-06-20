package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVStore
import kotlin.test.expect

/**
 * Creates an [UMN] backed by a file-based MV Store.
 */
fun createMvstoreUMN(): MvstoreUMN = MvstoreUMN(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer)
