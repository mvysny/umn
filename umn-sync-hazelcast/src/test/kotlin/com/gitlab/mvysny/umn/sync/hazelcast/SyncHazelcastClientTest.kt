package com.gitlab.mvysny.umn.sync.hazelcast

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.AutoTypeResolver
import com.gitlab.mvysny.umn.core.InMemoryUMN
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.sync.tester.*
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import java.util.*

class SyncHazelcastClientTest : DynaTest({
    lateinit var server: HazelcastInstance
    beforeGroup {
        val cfg: Config = Config().apply {
            networkConfig.interfaces.addInterface("127.0.0.1")
            networkConfig.port = 5051
        }
        server = Hazelcast.newHazelcastInstance(cfg)
    }
    afterGroup { server.shutdown() }

    lateinit var topic: String
    beforeEach { topic = "topic-${UUID.randomUUID()}" }

    fun newClient() = SyncHazelcastClient("127.0.0.1:5051", topic)

    globalLogClientTestBattery { newClient() }

    if (true) {
        writerReaderTestBattery("in-memory", 1, { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer) }, { newClient() })

        writerReaderTestBattery("in-memory", 10, { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer) }, { newClient() })

        writerReaderTestBattery("mvstore", 1, { createMvstoreUMN() }, { newClient() })

        writerReaderTestBattery("mvstore", 10, { createMvstoreUMN() }, { newClient() })

        syncTestBattery("in-memory", 2, { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer) }, { newClient() })

        syncTestBattery("in-memory", 10, { InMemoryUMN(AutoTypeResolver, JavaDocumentSerializer) }, { newClient() })

        syncTestBattery("mvstore", 2, { createMvstoreUMN() }, { newClient() })

        syncTestBattery("mvstore", 10, { createMvstoreUMN() }, { newClient() })
    }
})
