package com.gitlab.mvysny.umn.proxy

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream

data class Config(var port: Int = 8080,
                  var adminPort: Int = 8081,
                  var proxy: Proxy = Proxy()) {
    companion object {
        /**
         * Loads the configuration file from the following locations:
         *
         * * `./config.yaml` (the current working directory)
         * * `/etc/umnproxyserver/config.yaml`
         *
         * If no configuration file is found, a default config is loaded.
         */
        fun load(): Config {
            val files = listOf(File("config.yaml"), File("/etc/umnproxyserver/config.yaml"))
            val file = files.firstOrNull { it.exists() } ?: return newEmpty()
            return file.inputStream().use { loadFrom(it) }
        }
        fun loadFrom(i: InputStream): Config = Yaml().loadAs(i, Config::class.java) ?: Config()
        fun newEmpty(): Config = Config().apply { proxy.standalone = Standalone() }
    }
}

data class Proxy(var standalone: Standalone? = null) {
    fun createLogClientProvider(): GlobalLogClientProvider = when {
        standalone != null -> standalone!!.createLogClientProvider()
        else -> throw IllegalStateException("No log client provider has been defined, I don't know where to proxy the log commands!")
    }
}

data class Standalone(var db: String = "db") {
    fun createLogClientProvider(): GlobalLogClientProvider = MVStoreGlobalLogProvider(File(db).absoluteFile)
}
