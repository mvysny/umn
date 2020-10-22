package com.gitlab.mvysny.umn.sync.tester

import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.ProcessInitException
import org.zeroturnaround.exec.ProcessResult
import java.io.Closeable
import java.math.BigInteger

/**
 * Utility functions to control the docker daemon.
 */
object Docker {
    /**
     * Checks whether the "docker" command-line tool is available.
     */
    val isPresent: Boolean by lazy {
        try {
            ProcessExecutor().command("docker", "version").execute().exitValue == 0
        } catch (e: ProcessInitException) {
            if (e.errorCode == 2) {
                // no such file or directory
                false
            } else {
                throw e
            }
        }
    }
}

/**
 * Runs given [command]; if the command fails with exit code different than 0 throws an exception with the command stdout+stderr.
 * @return stdout
 */
fun exec(command: String): String {
    val commands: List<String> = command.split(' ')
    val result: ProcessResult = ProcessExecutor().command(commands).readOutput(true).execute()
    check(result.exitValue == 0) { "${result.outputUTF8()}\nProcess failed with ${result.exitValue}" }
    return result.outputUTF8()
}

/**
 * Represents the container ID: a 64-character long hexadecimal number.
 */
data class ContainerId(val id: String) {
    init {
        require(id.length == 64) { "$id is not 64 characters long but ${id.length}" }
        BigInteger(id, 16)  // try parse as a hex number
    }
}

/**
 * Represents a running container.
 * @param image the docker image to pull
 * @param dockerCommand the `docker run --rm -d ...` command. Must return the container ID as a response.
 */
class Container(image: String, dockerCommand: String) : Closeable {
    override fun close() {
        stop()
    }

    val id: ContainerId
    init {
        exec("docker pull $image")
        val id: String = exec(dockerCommand).trim()
        this.id = ContainerId(id)
    }

    /**
     * Checks whether the container is still running.
     */
    fun isRunning(): Boolean = exec("docker ps --no-trunc").contains(id.id)

    /**
     * Stops the running container, blocking until the container is stopped. Does nothing if
     * the container is not running.
     */
    fun stop() {
        while (isRunning()) {
            exec("docker stop ${id.id}")
            // pause a bit, to avoid this failure:
            // java.lang.IllegalStateException: docker: Error response from daemon: Conflict. The container name "/testing_container" is already
            // in use by container 613ff9e5f3de3312dc8ce33f0d3d3d7b40289c04e70c551179b87f835c2ebf3a. You have to remove (or rename) that
            // container to be able to reuse that name..
            Thread.sleep(500)
        }
    }
}
