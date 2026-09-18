package org.openscore.provider

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for suspending work. A failure becomes a [Result] like `runCatching`, but a
 * [CancellationException] is rethrown: a caller whose scope was cancelled must stop, not carry
 * on as if the network had merely failed and keep emitting or fetching after its collector left.
 *
 * Not inline: the core's Android target is built for a newer JVM than the app, and inline
 * bytecode cannot cross that boundary.
 */
public suspend fun <T> runCatchingUnlessCancelled(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
