package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.DocumentSerializer
import com.gitlab.mvysny.umn.core.LogRecord
import com.gitlab.mvysny.umn.core.TypeToClassResolver
import com.gitlab.mvysny.umn.core.closeQuietly
import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import java.io.Closeable
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses a local [MVStore] to store the log and the users. Doesn't make much sense on its own, but makes tons of sense when acting as a
 * storage for the proxy server.
 *
 * The database structure is as follows:
 * * The `users` map maps `String` username to `String` hashed password (produced by [PasswordHash.createHash].
 * * The `$user__log` map maps `Long` ordinal to [SerializedLogRecord]. The ordering is strict (the key starts at 0 and increments by 1).
 *
 * Thread-safe.
 * @property db the underlying database.
 */
class MVStoreGlobalLogClient internal constructor(val owner: MVStoreGlobalLog, val username: String, private val password: String) : GlobalLogClient {
    override val isStarted: Boolean
        get() = started.get() && !closed.get()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    internal var addCallback: () -> Unit = {}
    internal var receiveCallback: (record: LogRecord) -> Unit = {}

    /**
     * We have broadcasted (invoked [receiveCallback]) for log items up to this pointer, including.
     */
    private var currentLogPointer: Long = 0L
    private lateinit var log: MVMap<Long, SerializedLogRecord>

    private fun checkNotClosed() {
        check(!closed.get()) { "Already closed" }
    }

    private fun checkNotStarted() {
        check(!started.get()) { "Already started" }
    }

    override fun start() {
        checkNotClosed()
        check(started.compareAndSet(false, true)) { "Already started" }

        // verify the username and password
        val passwordHash = owner.db.openMap<String, String>("users")[username] ?: throw InvalidUsernamePasswordException("No such user: $username")
        if (!PasswordHash.validatePassword(password, passwordHash)) {
            throw InvalidUsernamePasswordException("Invalid password for $username")
        }

        // everything seems okay, notify owner that we are started
        owner.startClient(this)

        // open the log and broadcast any unsent items
        log = owner.db.openMap("${username}__log")
        onNewItems()
    }

    override fun setAddCallback(callback: () -> Unit) {
        checkNotStarted()
        addCallback = callback
    }

    override fun setReceiverCallback(rewind: Long, callback: (record: LogRecord) -> Unit) {
        require(rewind >= 0L) { "Parameter rewind: invalid value $rewind - must be 0 or greater" }
        checkNotStarted()
        currentLogPointer = rewind
        receiveCallback = callback
    }

    override fun addAsync(record: SerializedLogRecord) {
        check(started.get()) { "Not yet started" }
        checkNotClosed()
        log.append(record)
        addCallback()
        owner.fireNewItemNotification(username)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            owner.close(this)
        }
    }

    @Synchronized
    internal fun onNewItems() {
        log.keyIterator(currentLogPointer).forEach { key ->
            val record = checkNotNull(log[key])
            receiveCallback(LogRecord.fromByteArray(record, owner.serializer, owner.typeToClassResolver))
            currentLogPointer = key + 1
        }
    }
}

/**
 * Finds the next free key and puts the [value] there. Thread safe. If the map is empty, starts with 0.
 *
 * The key will be bigger than any of the ID currently present in the map (the function doesn't check for "holes" in the ID keys).
 * The key will however be smallest possible (generally bigger by 1 than the biggest ID in the map, but may be bigger if the function is called by multiple threads at once).
 * @return the key where the value has been stored.
 */
fun <V: Any> MVMap<Long, V>.append(value: V): Long {
    var last = lastKey() ?: -1
    while(putIfAbsent(++last, value) != null) {}
    return last
}

/**
 * Thread-safe. Stores logs for multiple users into given [db]. Call [newClient] to provide new client.
 *
 * Closes [db] when closed.
 */
class MVStoreGlobalLog(val db: MVStore, val serializer: DocumentSerializer, val typeToClassResolver: TypeToClassResolver) : Closeable {
    private val startedClients = ConcurrentHashMap<String, BlockingQueue<MVStoreGlobalLogClient>>()
    private val closed = AtomicBoolean(false)

    /**
     * Creates a new client for given user. Authenticates the user with given [username] and [password] lazily, in the
     * [MVStoreGlobalLogClient.start] function.
     */
    fun newClient(username: String, password: String) : GlobalLogClient = MVStoreGlobalLogClient(this, username, password)

    internal fun startClient(client: MVStoreGlobalLogClient) {
        val clientsForUsername = startedClients.getOrPut(client.username) { LinkedBlockingQueue() }
        clientsForUsername.add(client)
    }

    internal fun close(client: MVStoreGlobalLogClient) {
        startedClients[client.username]?.remove(client)
    }

    internal fun fireNewItemNotification(username: String) {
        startedClients[username]?.forEach { it.onNewItems() }
    }

    /**
     * Creates a new user with specified [username] and [password]. If the user exists, the password is updated.
     */
    fun createOrUpdateUser(username: String, password: String) {
        db.openMap<String, String>("users")[username] = PasswordHash.createHash(password)
    }

    /**
     * Deletes the user along with the entire log.
     * @param username the name of the user
     */
    fun deleteUser(username: String) {
        db.openMap<String, String>("users").remove(username)
        db.removeMap(db.openMap<Long, SerializedLogRecord>("${username}__log"))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            startedClients.values.flatten().forEach { it.closeQuietly() }
            startedClients.clear()
            db.close()
        }
    }
}
