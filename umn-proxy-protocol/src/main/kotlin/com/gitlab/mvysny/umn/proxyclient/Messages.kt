package com.gitlab.mvysny.umn.proxyclient

import com.gitlab.mvysny.umn.proxyclient.AbstractMessage.*
import com.gitlab.mvysny.umn.proxyclient.AbstractMessage.Companion.WS_INVALID_USERNAME_PASSWORD
import com.gitlab.mvysny.umn.proxyclient.AbstractMessage.Companion.WS_SERVER_ERROR
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The messages sent over a WebSocket as binary blobs, written field by field with [DataOutputStream]; the protocol
 * generally resembles a RPC of the [com.gitlab.mvysny.umn.sync.GlobalLogClient] interface.
 *
 * A record is opaque: [AddAsync] and [OnLogRecord] carry a `LogRecord`'s bytes, length-prefixed, and neither this
 * protocol nor the proxy server ever decodes them.
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
public sealed class AbstractMessage {

    public abstract fun writeTo(dout: DataOutputStream)

    final override fun toString(): String = javaClass.simpleName

    public fun asByteArray(): ByteArray = ByteArrayOutputStream().apply {
        this@AbstractMessage.writeTo(DataOutputStream(this))
    }.toByteArray()

    /**
     * Sent by the server upon connection open, to notify the client that the username/password is OK and
     * the server is ready to listen for requests.
     */
    public object Started : AbstractMessage() {
        override fun writeTo(dout: DataOutputStream) {
            dout.writeByte(TYPE_STARTED)
        }
    }

    /**
     * Sent by the client to notify the Global Log of a new log record. The server will reply with [OnAdded] when the record
     * is stored safely on the server.
     */
    public class AddAsync(public val record: ByteArray) : AbstractMessage() {
        override fun writeTo(dout: DataOutputStream) {
            dout.writeByte(TYPE_ADD_ASYNC)
            dout.writeInt(record.size)
            dout.write(record)
        }
        public companion object {
            public fun read(din: DataInputStream): AddAsync {
                val bytes = ByteArray(din.readInt())
                din.readFully(bytes)
                return AddAsync(bytes)
            }
        }
    }

    /**
     * Sent by the server as an ACK that [AddAsync] has been received.
     */
    public object OnAdded : AbstractMessage() {
        override fun writeTo(dout: DataOutputStream) {
            dout.writeByte(TYPE_ON_ADDED)
        }
    }

    /**
     * Sent by the server to all clients, notifying them that a record has been added to the global log (via the [AddAsync] message).
     * @property record the added record, may be null in case a hole has been punched into the log. See [com.gitlab.mvysny.umn.sync.GlobalLogClient]
     * for details. A client older than UMN 0.15 can't read a null record, so the proxy server never punches holes.
     */
    public class OnLogRecord(public val record: ByteArray?) : AbstractMessage() {
        override fun writeTo(dout: DataOutputStream) {
            dout.writeByte(TYPE_ON_LOG_RECORD)
            if (record == null) {
                dout.writeInt(0)
            } else {
                dout.writeInt(record.size)
                dout.write(record)
            }
        }
        public companion object {
            public fun read(din: DataInputStream): OnLogRecord {
                val size = din.readInt()
                val bytes: ByteArray? = if (size == 0) null else ByteArray(size).apply { din.readFully(this) }
                return OnLogRecord(bytes)
            }
        }
    }

    public companion object {
        public val WS_INVALID_USERNAME_PASSWORD: Int = 4000
        public val WS_SERVER_ERROR: Int = 4001

        /**
         * [Started]
         */
        private val TYPE_STARTED = 1
        /**
         * [AddAsync]
         */
        private val TYPE_ADD_ASYNC = 2
        /**
         * [OnAdded]
         */
        private val TYPE_ON_ADDED = 3
        /**
         * [OnLogRecord]
         */
        private val TYPE_ON_LOG_RECORD = 4

        /**
         * Reconstructs the message from given [bytes].
         */
        public fun fromByteArray(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): AbstractMessage {
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
