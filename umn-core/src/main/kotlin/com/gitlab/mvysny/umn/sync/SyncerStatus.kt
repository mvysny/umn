package com.gitlab.mvysny.umn.sync

/**
 * What a [Syncer] is doing. A started syncer cycles through [Connecting], [Connected] and, once the
 * connection fails, [Offline], until it is [Stopped] or [Failed]:
 *
 * ```
 * Stopped → Connecting → Connected → Offline → Connecting → Offline → Connecting → Connected …
 *         start()                  connection lost        server down
 * ```
 */
public sealed class SyncerStatus {
    /**
     * Not started yet, or [Syncer.stop]ped.
     */
    public data object Stopped : SyncerStatus()

    /**
     * Taking a new client from the client provider and starting it.
     */
    public data object Connecting : SyncerStatus()

    /**
     * Connected and pushing local changes. Changes from other nodes may still be arriving: connected is not caught up.
     */
    public data object Connected : SyncerStatus()

    /**
     * The connection failed; the syncer reconnects in [retryInMillis].
     * @property cause null when the connection closed without an exception, e.g. the server closed it; a
     * [LogRecordNotAppliedException] when a record from another node didn't apply here.
     */
    public data class Offline(val cause: Throwable?, val retryInMillis: Long) : SyncerStatus()

    /**
     * The server rejected the credentials and the syncer stopped syncing for good. Once the client provider
     * hands out clients with the fixed credentials, [Syncer.stop] and [Syncer.start] it.
     */
    public data class Failed(val cause: InvalidUsernamePasswordException) : SyncerStatus()
}

/**
 * A record from the global log failed to apply to this node: say an older app version got a document class it can't
 * deserialize, or an index's key getter threw. Syncing stalls at the record rather than skip it, so this node never
 * diverges: the [Syncer] drops the connection, reports [SyncerStatus.Offline] with this as the cause, and retries the
 * record after a back-off, until an app update makes it apply.
 *
 * @property logIndex the record's position in the global log.
 */
public class LogRecordNotAppliedException(public val logIndex: Long, cause: Exception) :
    Exception("Failed to apply global log record #$logIndex; syncing stalls until it applies", cause)

/**
 * Hears every [SyncerStatus] change of a [Syncer]; see [Syncer.setStatusListener].
 */
public fun interface SyncerStatusListener {
    public fun onStatusChanged(status: SyncerStatus)
}
