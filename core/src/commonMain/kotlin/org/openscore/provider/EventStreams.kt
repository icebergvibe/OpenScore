package org.openscore.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openscore.net.ServerSentEvent
import kotlin.time.Duration

/**
 * Feeds [onEvent] from one upstream connection until [onEvent] answers false, the upstream
 * closes or fails, [quietTimeout] passes in silence, or [onQuiet] (asked after every
 * [checkInterval] of silence) answers false. Returns normally in every one of those cases.
 *
 * The reader is a child whose failure ends this loop, never the caller's flow: a reset, a
 * timeout or a refused connect is, for a live view, a reason to reconnect from a fresh
 * snapshot. Shared by the push providers because the two copies this replaced had drifted on
 * exactly that point, and the one that let the failure through ended Bundesliga's live view on
 * the first dropped connection.
 */
public suspend fun consumeStream(
    stream: Flow<ServerSentEvent>,
    checkInterval: Duration,
    quietTimeout: Duration = checkInterval,
    onQuiet: suspend () -> Boolean = { true },
    onEvent: suspend (ServerSentEvent) -> Boolean,
): Unit = coroutineScope {
    val events = Channel<ServerSentEvent>(Channel.BUFFERED)
    val reader = launch {
        try {
            stream.collect { events.send(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A reset or a timeout: the caller reconnects from a fresh snapshot.
        } finally {
            events.close()
        }
    }
    try {
        var silence = Duration.ZERO
        while (true) {
            val received = withTimeoutOrNull(checkInterval) { events.receiveCatching() }
            if (received == null) {
                silence += checkInterval
                if (silence >= quietTimeout || !onQuiet()) break
                continue
            }
            val event = received.getOrNull() ?: break
            silence = Duration.ZERO
            if (!onEvent(event)) break
        }
    } finally {
        reader.cancelAndJoin()
    }
}
