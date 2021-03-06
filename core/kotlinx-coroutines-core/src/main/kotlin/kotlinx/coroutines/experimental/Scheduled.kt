/*
 * Copyright 2016-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kotlinx.coroutines.experimental

import kotlinx.coroutines.experimental.JobSupport.CompletedExceptionally
import kotlinx.coroutines.experimental.selects.SelectBuilder
import kotlinx.coroutines.experimental.selects.select
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.experimental.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.experimental.intrinsics.suspendCoroutineOrReturn

/**
 * Runs a given suspending [block] of code inside a coroutine with a specified timeout and throws
 * [TimeoutCancellationException] if timeout was exceeded.
 *
 * The code that is executing inside the [block] is cancelled on timeout and the active or next invocation of
 * cancellable suspending function inside the block throws [TimeoutCancellationException].
 * Even if the code in the block suppresses [TimeoutCancellationException], it
 * is still thrown by `withTimeout` invocation.
 *
 * The sibling function that does not throw exception on timeout is [withTimeoutOrNull].
 * Note, that timeout action can be specified for [select] invocation with [onTimeout][SelectBuilder.onTimeout] clause.
 *
 * This function delegates to [Delay.invokeOnTimeout] if the context [CoroutineDispatcher]
 * implements [Delay] interface, otherwise it tracks time using a built-in single-threaded scheduled executor service.
 *
 * @param time timeout time
 * @param unit timeout unit (milliseconds by default)
 */
public suspend fun <T> withTimeout(time: Long, unit: TimeUnit = TimeUnit.MILLISECONDS, block: suspend CoroutineScope.() -> T): T {
    require(time >= 0) { "Timeout time $time cannot be negative" }
    if (time <= 0L) throw CancellationException("Timed out immediately")
    return suspendCoroutineOrReturn { cont: Continuation<T> ->
        setupTimeout(TimeoutCoroutine(time, unit, cont), block)
    }
}

private fun <U, T: U> setupTimeout(
    coroutine: TimeoutCoroutine<U, T>,
    block: suspend CoroutineScope.() -> T
): Any? {
    // schedule cancellation of this coroutine on time
    val cont = coroutine.cont
    val context = cont.context
    coroutine.disposeOnCompletion(context.delay.invokeOnTimeout(coroutine.time, coroutine.unit, coroutine))
    coroutine.initParentJob(context[Job])
    // restart block using new coroutine with new job,
    // however start it as undispatched coroutine, because we are already in the proper context
    val result = try {
        block.startCoroutineUninterceptedOrReturn(receiver = coroutine, completion = coroutine)
    } catch (e: Throwable) {
        CompletedExceptionally(e)
    }
    return when {
        result == COROUTINE_SUSPENDED -> COROUTINE_SUSPENDED
        coroutine.makeCompleting(result, MODE_IGNORE) -> {
            if (result is CompletedExceptionally) throw result.exception else result
        }
        else -> COROUTINE_SUSPENDED
    }
}

/**
 * @suppress **Deprecated**: for binary compatibility only
 */
@Deprecated("for binary compatibility only", level=DeprecationLevel.HIDDEN)
public suspend fun <T> withTimeout(time: Long, unit: TimeUnit = TimeUnit.MILLISECONDS, block: suspend () -> T): T =
    withTimeout(time, unit) { block() }

private open class TimeoutCoroutine<U, in T: U>(
    @JvmField val time: Long,
    @JvmField val unit: TimeUnit,
    @JvmField val cont: Continuation<U>
) : AbstractCoroutine<T>(cont.context, active = true), Runnable, Continuation<T> {
    override val defaultResumeMode: Int get() = MODE_DIRECT

    @Suppress("LeakingThis")
    override fun run() {
        cancel(TimeoutCancellationException(time, unit, this))
    }

    @Suppress("UNCHECKED_CAST")
    override fun afterCompletion(state: Any?, mode: Int) {
        if (state is CompletedExceptionally)
            cont.resumeWithExceptionMode(state.exception, mode)
        else
            cont.resumeMode(state as T, mode)
    }

    override fun nameString(): String =
        "${super.nameString()}($time $unit)"
}

/**
 * Runs a given suspending block of code inside a coroutine with a specified timeout and returns
 * `null` if this timeout was exceeded.
 *
 * The code that is executing inside the [block] is cancelled on timeout and the active or next invocation of
 * cancellable suspending function inside the block throws [TimeoutCancellationException].
 * Even if the code in the block suppresses [TimeoutCancellationException], this
 * invocation of `withTimeoutOrNull` still returns `null`.
 *
 * The sibling function that throws exception on timeout is [withTimeout].
 * Note, that timeout action can be specified for [select] invocation with [onTimeout][SelectBuilder.onTimeout] clause.
 *
 * This function delegates to [Delay.invokeOnTimeout] if the context [CoroutineDispatcher]
 * implements [Delay] interface, otherwise it tracks time using a built-in single-threaded scheduled executor service.
 *
 * @param time timeout time
 * @param unit timeout unit (milliseconds by default)
 */
public suspend fun <T> withTimeoutOrNull(time: Long, unit: TimeUnit = TimeUnit.MILLISECONDS, block: suspend CoroutineScope.() -> T): T? {
    require(time >= 0) { "Timeout time $time cannot be negative" }
    if (time <= 0L) return null
    return suspendCoroutineOrReturn { cont: Continuation<T?> ->
        setupTimeout(TimeoutOrNullCoroutine(time, unit, cont), block)
    }
}

/**
 * @suppress **Deprecated**: for binary compatibility only
 */
@Deprecated("for binary compatibility only", level=DeprecationLevel.HIDDEN)
public suspend fun <T> withTimeoutOrNull(time: Long, unit: TimeUnit = TimeUnit.MILLISECONDS, block: suspend () -> T): T? =
    withTimeoutOrNull(time, unit) { block() }

private class TimeoutOrNullCoroutine<T>(
    time: Long,
    unit: TimeUnit,
    cont: Continuation<T?>
) : TimeoutCoroutine<T?, T>(time, unit, cont) {
    @Suppress("UNCHECKED_CAST")
    override fun afterCompletion(state: Any?, mode: Int) {
        if (state is CompletedExceptionally) {
            val exception = state.exception
            if (exception is TimeoutCancellationException && exception.coroutine === this)
                cont.resumeMode(null, mode) else
                cont.resumeWithExceptionMode(exception, mode)
        } else
            cont.resumeMode(state as T, mode)
    }
}

/**
 * This exception is thrown by [withTimeout] to indicate timeout.
 */
@Suppress("DEPRECATION")
public class TimeoutCancellationException internal constructor(
    message: String,
    @JvmField internal val coroutine: Job?
) : TimeoutException(message) {
    /**
     * Creates timeout exception with a given message.
     */
    public constructor(message: String) : this(message, null)
}

/**
 * @suppress **Deprecated**: Renamed to TimeoutCancellationException
 */
@Deprecated("Renamed to TimeoutCancellationException", replaceWith = ReplaceWith("TimeoutCancellationException"))
public open class TimeoutException(message: String) : CancellationException(message)

@Suppress("FunctionName")
private fun TimeoutCancellationException(
    time: Long,
    unit: TimeUnit,
    coroutine: Job
) : TimeoutCancellationException = TimeoutCancellationException("Timed out waiting for $time $unit", coroutine)
