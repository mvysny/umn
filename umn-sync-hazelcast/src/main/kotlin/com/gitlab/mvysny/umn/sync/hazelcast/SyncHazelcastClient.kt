package com.gitlab.mvysny.umn.sync.hazelcast

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.core.HazelcastInstance

class SyncHazelcastClient(hostPort: String) {
    init {
        val clientConfig = ClientConfig().apply {
            networkConfig.addresses.add(hostPort)
        }
        val client: HazelcastInstance = HazelcastClient.newHazelcastClient(clientConfig)
    }
}
