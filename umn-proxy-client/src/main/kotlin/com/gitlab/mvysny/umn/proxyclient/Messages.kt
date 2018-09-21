package com.gitlab.mvysny.umn.proxyclient

import com.gitlab.mvysny.umn.sync.SerializedLogRecord
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.IllegalArgumentException

/**
 * The messages sent over a WebSocket as binary serialized blobs; the protocol generally resembles a RPC of the
 * [com.gitlab.mvysny.umn.sync.GlobalLogClient] interface.
 *
 * # Communication protocol
 *
 * 1. When opening a web socket communication, the `username`, `password` and `rewind` must be sent as headers. If these headers are missing or the user/pass
 * doesn't match, the web socket connection is closed immediately, with [WS_INVALID_USERNAME_PASSWORD].
 * 2. Upon opening a web socket, the server opens its own [com.gitlab.mvysny.umn.sync.GlobalLogClient]
 * delegate and starts it. If the username/password is invalid, the web socket connection is closed immediately, with [WS_INVALID_USERNAME_PASSWORD].
 * 3. If the client succeeded to be started server-side and everything is OK, the server responds by [Started] or [OnLogRecord]. If something failed,
 * the connection is closed with [WS_SERVER_ERROR].
 * 4. Then the server starts sending a stream of [OnLogRecord] to catch up the client with messages he did not yet seen. This depends on the
 * `rewind` parameter.
 * 5. When the client's [com.gitlab.mvysny.umn.sync.GlobalLogClient.addAsync] is invoked, the client sends [AddAsync] to which the server responds with [OnAdded].
 * 6. Server then periodically sends [OnLogRecord] to inform all clients of an added log record.
 *
 * At any time the connection may be closed by the server in case of an error. In such case the client must not repeat, but instead must fail
 * with a non-recoverable exception.
 */
sealed class AbstractMessage {

    abstract fun writeTo(dout: DataOutputStream)

    final override fun toString(): String = javaClass.simpleName

    fun asByteArray(): ByteArray = ByteArrayOutputStream().apply {
        this@AbstractMessage.writeTo(DataOutputStream(this))
    }.toByteArray()

    object Started : AbstractMessage() {
        override fun writeTo(dout: DataOutputStream) {
            dout.writeByte(TYPE_STARTED)
        }
    }

    class AddAsync(val record: SerializedLogRecord) : AbstractMessage() {
        override fun writeTo(dout: DataOutputStream) {
            dout.writeByte(TYPE_ADD_ASYNC)
            dout.writeInt(record.size)
            dout.write(record)
        }
        companion object {
            fun read(din: DataInputStream): AddAsync {
                val bytes = ByteArray(din.readInt())
                din.readFully(bytes)
                return AddAsync(bytes)
            }
        }
    }

    object OnAdded : AbstractMessage() {
        override fun writeTo(dout: DataOutputStream) {
            dout.writeByte(TYPE_ON_ADDED)
        }
    }

    class OnLogRecord(val record: SerializedLogRecord) : AbstractMessage() {
        override fun writeTo(dout: DataOutputStream) {
            dout.writeByte(TYPE_ON_LOG_RECORD)
            dout.writeInt(record.size)
            dout.write(record)
        }
        companion object {
            fun read(din: DataInputStream): OnLogRecord {
                val bytes = ByteArray(din.readInt())
                din.readFully(bytes)
                return OnLogRecord(bytes)
            }
        }
    }

    companion object {
        val WS_INVALID_USERNAME_PASSWORD = 4000
        val WS_SERVER_ERROR = 4001

        private val TYPE_STARTED = 1
        private val TYPE_ADD_ASYNC = 2
        private val TYPE_ON_ADDED = 3
        private val TYPE_ON_LOG_RECORD = 4

        fun fromByteArray(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): AbstractMessage {
            require(bytes.isNotEmpty() && offset < length) { "Empty array: ${bytes.size}, offset=$offset length=$length" }
            val din = DataInputStream(bytes.inputStream(offset, length))
            val type = din.readByte().toInt()
            return when (type) {
                TYPE_STARTED -> Started
                TYPE_ADD_ASYNC -> AddAsync.read(din)
                TYPE_ON_ADDED -> OnAdded
                TYPE_ON_LOG_RECORD -> OnLogRecord.read(din)
                else -> throw IllegalArgumentException("Invalid type received: $type")
            }
        }
    }
}
