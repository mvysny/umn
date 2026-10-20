package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVStore
import java.io.File

/**
 * Creates an [UMN] backed by a file-based MV Store.
 */
fun createMvstoreUMN(dbFileName: String = File.createTempFile("tmp", null).absolutePath): MvstoreUMN = MvstoreUMN(MVStore.open(dbFileName), JavaDocumentSerializer,
    AutoTypeResolver)
