package com.gitlab.mvysny.umn.sync

import com.gitlab.mvysny.umn.core.closeQuietly
import org.h2.mvstore.MVMap
import org.h2.mvstore.MVStore
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
class MVStoreGlobalLogClient internal constructor(val owner: MVStoreGlobalLog, val username: String, private val password: String) : GlobalLogClient {
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
        val passwordHash = owner.db.openMap<String, String>("users")[username] ?: throw InvalidUsernamePasswordException("No such user: $username")
        if (!PasswordHash.validatePassword(password, passwordHash)) {
            throw InvalidUsernamePasswordException("Invalid password for $username")
        }

        // everything seems okay, notify owner that we are started
        owner.startClient(this)

        // open the log and broadcast any unsent items
        log = owner.getLogMap(username)
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
fun <V: Any> MVMap<Long, V>.append(value: V): Long {
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
 * * `{username}__log`: contains the global log for given user. Maps the log record ordinal to the serialized log record.
 * * `properties`: various properties of the MVStore: `globalLogStorageUUID` of type [UUID] is the global log storage UUID.
 */
class MVStoreGlobalLog(val db: MVStore) : Closeable {
    private val startedClients = ConcurrentHashMap<String, BlockingQueue<MVStoreGlobalLogClient>>()
    private val closed = AtomicBoolean(false)

    /**
     * Creates a new client for given user. Authenticates the user with given [username] and [password] lazily, in the
     * [MVStoreGlobalLogClient.start] function.
     */
    fun newClient(username: String, password: String) : GlobalLogClient =
            MVStoreGlobalLogClient(this, username, password)

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

    fun listUsers(): List<String> = db.openMap<String, String>("users").keyList().filterNotNull()

    /**
     * Deletes the user along with the entire log.
     * @param username the name of the user
     */
    fun deleteUser(username: String) {
        db.openMap<String, String>("users").remove(username)
        db.removeMap(getLogMap(username))
    }

    fun getLogMap(username: String) = db.openMap<Long, SerializedLogRecord>("${username}__log")

    fun deleteAllUsers() {
        listUsers().forEach { deleteUser(it) }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            startedClients.values.flatten().forEach { it.closeQuietly() }
            startedClients.clear()
            db.close()
        }
    }

    fun getLogSizes(): Map<String, Long> = listUsers().associateBy({it}, { getLogMap(it).sizeAsLong() })

    val activeClientCount: Int get() = startedClients.values.sumBy { it.size }
}
