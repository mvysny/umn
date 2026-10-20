package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.closeQuietly
import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.util.*
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
 * @property owner the owner log
 */
public class MVStoreGlobalLogClient internal constructor(public val owner: MVStoreGlobalLog, public val username: String, private val password: String) : GlobalLogClient {
    override val isStarted: Boolean
        get() = started.get() && !closed.get()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    internal var addCallback: () -> Unit = {}
    internal var receiveCallback: (record: SerializedLogRecord?) -> Unit = {}

    /**
     * We have broadcasted (invoked [receiveCallback]) for log items up to this pointer, including.
     */
    private var currentLogPointer: Long = 0L
    /**
     * The global log itself. Maps the log record ordinal to the serialized log record. Currently the ID sequence is monotonic
     * (no IDs are missing and no holes are punched in the map).
     *
     * WARNING: If we eventually implement hole punching, make sure that null gets sent in [onNewItems] for every missing ID
     * in the ID sequence, otherwise the client will not update its sync pointer properly and will seek to earlier item next
     * time the client connects.
     */
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
        val passwordHash = owner.users[username] ?: throw InvalidUsernamePasswordException("No such user: $username")
        if (!PasswordHash.validatePassword(password, passwordHash)) {
            throw InvalidUsernamePasswordException("Invalid password for $username")
        }

        // open the log before registering: a record added by another client notifies this one right away
        log = owner.getLogMap(username)
        owner.startClient(this)
        onNewItems()
    }

    override fun setAddCallback(callback: () -> Unit) {
        checkNotStarted()
        addCallback = callback
    }

    override fun setReceiverCallback(rewind: Long, callback: (record: SerializedLogRecord?) -> Unit) {
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
            receiveCallback(record)
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
public fun <V: Any> MVMap<Long, V>.append(value: V): Long {
    var last = lastKey() ?: -1
    while(putIfAbsent(++last, value) != null) {}
    return last
}

/**
 * Thread-safe. Stores logs for multiple users into given [db]. Call [newClient] to provide new client.
 *
 * Closes [db] when closed.
 *
 * Contains the following maps:
 * * `users`: maps String username to String hashed password
 * * `{username}__log`: contains the global log for given user. Maps the log record ordinal to the serialized log record. */
public class MVStoreGlobalLog(public val db: MVStore) : Closeable {
    private val startedClients = ConcurrentHashMap<String, BlockingQueue<MVStoreGlobalLogClient>>()
    private val closed = AtomicBoolean(false)

    /**
     * Creates a new client for given user. Authenticates the user with given [username] and [password] lazily, in the
     * [MVStoreGlobalLogClient.start] function.
     */
    public fun newClient(username: String, password: String) : GlobalLogClient =
            MVStoreGlobalLogClient(this, username, password)

    internal fun startClient(client: MVStoreGlobalLogClient) {
        val clientsForUsername = startedClients.getOrPut(client.username) { LinkedBlockingQueue() }
        clientsForUsername.add(client)
    }

    internal fun close(client: MVStoreGlobalLogClient) {
        startedClients[client.username]?.remove(client)
    }

    /**
     * Notifies every started client of [username], even when one fails: the others would miss the record until the
     * next one arrives. A failed client catches up on its next notification.
     */
    internal fun fireNewItemNotification(username: String) {
        startedClients[username]?.forEach { client ->
            try {
                client.onNewItems()
            } catch (e: Exception) {
                log.warn("$username: failed to announce new records to a client", e)
            }
        }
    }

    /**
     * Creates a new user with specified [username] and [password]. If the user exists, the password is updated.
     */
    public fun createOrUpdateUser(username: String, password: String) {
        users[username] = PasswordHash.createHash(password)
    }

    public fun listUsers(): List<String> = users.keyList().filterNotNull()

    /**
     * Deletes the user along with the entire log.
     * @param username the name of the user
     */
    public fun deleteUser(username: String) {
        users.remove(username)
        db.removeMap(getLogMap(username))
    }

    public fun getLogMap(username: String): MVMap<Long, SerializedLogRecord> = openMap("${username}__log")

    internal val users: MVMap<String, String> get() = openMap("users")

    /**
     * Opens every map of this log: MVStore's `openMap()` isn't atomic while the map doesn't exist yet. Two threads
     * creating one map get a map each, and the records written through the one whose name binding loses are orphaned.
     */
    @Synchronized
    private fun <K, V> openMap(name: String): MVMap<K, V> = db.openMap(name)

    public fun deleteAllUsers() {
        listUsers().forEach { deleteUser(it) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            startedClients.values.flatten().forEach { it.closeQuietly() }
            startedClients.clear()
            db.close()
        }
    }

    public fun getLogSizes(): Map<String, Long> = listUsers().associateBy({it}, { getLogMap(it).sizeAsLong() })

    /**
     * Estimates current client count.
     */
    public val activeClientCount: Int get() = startedClients.values.sumOf { it.size }

    private companion object {
        @JvmStatic
        private val log = LoggerFactory.getLogger(MVStoreGlobalLog::class.java)
    }
}
