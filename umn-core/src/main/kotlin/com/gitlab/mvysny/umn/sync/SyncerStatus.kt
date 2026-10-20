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
     * @property cause null when the connection closed without an exception, e.g. the server closed it.
     */
    public data class Offline(val cause: Throwable?, val retryInMillis: Long) : SyncerStatus()

    /**
     * The server rejected the credentials and the syncer stopped syncing for good. Once the client provider
     * hands out clients with the fixed credentials, [Syncer.stop] and [Syncer.start] it.
     */
    public data class Failed(val cause: InvalidUsernamePasswordException) : SyncerStatus()
}

/**
 * Hears every [SyncerStatus] change of a [Syncer]; see [Syncer.setStatusListener].
 */
public fun interface SyncerStatusListener {
    public fun onStatusChanged(status: SyncerStatus)
}
