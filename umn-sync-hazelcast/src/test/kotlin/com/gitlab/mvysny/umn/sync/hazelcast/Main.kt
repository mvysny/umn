package com.gitlab.mvysny.umn.sync.hazelcast

import com.hazelcast.client.HazelcastClient
import com.hazelcast.client.config.ClientConfig
import com.hazelcast.config.Config
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance

fun main(args: Array<String>) {
    try {
        testHazelcast()
    } catch (e: Exception) {
        println("EXCEPTION! $e")
        e.printStackTrace()
    }
}

private fun testHazelcast() {
    val cfg: Config = Config().apply {
        networkConfig.interfaces.addInterface("127.0.0.1")
        networkConfig.port = 5051
    }
    val server = Hazelcast.newHazelcastInstance(cfg)
    try {

        val clientConfig = ClientConfig().apply {
            networkConfig.addresses.add("127.0.0.1:5051")
        }

        val client: HazelcastInstance = HazelcastClient.newHazelcastClient(clientConfig)
        try {
            client.getList<String>("mytopic").addAll(listOf("Foo", "Bar", "Baz"))
        } finally {
            client.shutdown()
        }

        val client2: HazelcastInstance = HazelcastClient.newHazelcastClient(clientConfig)
        try {
            println(client2.getList<String>("mytopic")[1])
        } finally {
            client2.shutdown()
        }
    } finally {
        server.shutdown()
    }
}

