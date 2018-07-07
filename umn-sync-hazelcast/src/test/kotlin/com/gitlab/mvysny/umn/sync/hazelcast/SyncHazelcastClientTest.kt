package com.gitlab.mvysny.umn.sync.hazelcast

import com.github.mvysny.dynatest.DynaTest
import com.gitlab.mvysny.umn.core.JavaDocumentSerializer
import com.gitlab.mvysny.umn.sync.tester.globalLogClientTestBattery
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

    fun newClient() = SyncHazelcastClient("127.0.0.1:5051", topic, JavaDocumentSerializer)

    globalLogClientTestBattery { newClient() }
})
