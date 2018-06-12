package com.gitlab.mvysny.umn.core

import org.h2.mvstore.MVStore

fun createMvstoreUMN(): MvstoreUMN = MvstoreUMN(MVStore.open(createTempFile().absolutePath), JavaDocumentSerializer)
