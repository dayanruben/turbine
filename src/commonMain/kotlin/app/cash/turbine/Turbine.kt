/*
 * Copyright (C) 2022 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.turbine

import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ChannelResult

internal const val debug = false

/**
 * A standalone [Turbine] suitable for usage in fakes or other external test components.
 */
public interface Turbine<T> : ReceiveTurbine<T> {
  /**
   * Returns the underlying [Channel]. The [Channel] will have a buffer size of [UNLIMITED].
   */
  public override fun asChannel(): Channel<T>

  /**
   * Closes the underlying [Channel]. After all items have been consumed, this [Turbine] will yield
   * [Event.Complete] if [cause] is null, and [Event.Error] otherwise.
   */
  public fun close(cause: Throwable? = null)

  /**
   * Add an item to the underlying [Channel] without blocking.
   *
   * This method is equivalent to:
   *
   * ```
   * if (!asChannel().trySend(item).isSuccess) error()
   * ```
   */
  public fun add(item: T)

  /**
   * Assert that the next event received was non-null and return it.
   * This function will not suspend. On JVM and Android, it will attempt to throw if invoked in a suspending context.
   *
   * @throws AssertionError if the next event was completion or an error.
   */
  public fun takeEvent(): Event<T>

  /**
   * Assert that the next event received was an item and return it.
   * This function will not suspend. On JVM and Android, it will attempt to throw if invoked in a suspending context.
   *
   * @throws AssertionError if the next event was completion or an error, or no event.
   */
  public fun takeItem(): T

  /**
   * Assert that the next event received is [Event.Complete].
   * This function will not suspend. On JVM and Android, it will attempt to throw if invoked in a suspending context.
   *
   * @throws AssertionError if the next event was completion or an error.
   */
  public fun takeComplete()

  /**
   * Assert that the next event received is [Event.Error], and return the error.
   * This function will not suspend. On JVM and Android, it will attempt to throw if invoked in a suspending context.
   *
   * @throws AssertionError if the next event was completion or an error.
   */
  public fun takeError(): Throwable
}

public operator fun <T> Turbine<T>.plusAssign(value: T) { add(value) }

/**
 * Construct a standalone [Turbine].
 *
 * @param timeout If non-null, overrides the current Turbine timeout for this [Turbine]. See also:
 * [withTurbineTimeout].
 * @param name If non-null, name is added to any exceptions thrown to help identify which [Turbine] failed.
 */
public fun <T> Turbine(
  timeout: Duration? = null,
  name: String? = null,
): Turbine<T> = ChannelTurbine(Channel(UNLIMITED), null, timeout, name)

internal class ChannelTurbine<T>(
  channel: Channel<T>,
  /** Non-null if [channel] is being populated by an external `Flow` collection. */
  private val collectJob: Job?,
  private val timeout: Duration?,
  private val name: String?,
) : Turbine<T> {
  private suspend fun <T> withTurbineTimeout(block: suspend () -> T): T {
    return if (timeout != null) {
      withTurbineTimeout(timeout) { block() }
    } else {
      block()
    }
  }

  private val channel = object : Channel<T> by channel {
    override fun tryReceive(): ChannelResult<T> {
      val result = channel.tryReceive()
      val event = result.toEvent()
      if (event is Event.Error || event is Event.Complete) ignoreRemainingEvents = true

      return result
    }

    override suspend fun receive(): T = try {
      channel.receive()
    } catch (e: Throwable) {
      ignoreRemainingEvents = true
      throw e
    }

    override suspend fun receiveCatching(): ChannelResult<T> {
      return channel.receiveCatching().also {
        if (it.toEvent()?.isTerminal == true) {
          ignoreRemainingEvents = true
        }
      }
    }

    override fun cancel(cause: CancellationException?) {
      collectJob?.cancel()
      channel.close(cause)
    }

    override fun close(cause: Throwable?): Boolean {
      collectJob?.cancel()
      return channel.close(cause)
    }
  }

  override fun asChannel(): Channel<T> = channel

  override fun add(item: T) {
    if (!channel.trySend(item).isSuccess) throw IllegalStateException("Attempt to add item to a closed Turbine${name?.let { " named $it" } ?: ""}.")
  }

  @OptIn(DelicateCoroutinesApi::class)
  override suspend fun cancel() {
    if (!channel.isClosedForSend) ignoreTerminalEvents = true
    channel.cancel()
    collectJob?.cancelAndJoin()
  }

  @OptIn(DelicateCoroutinesApi::class)
  override fun close(cause: Throwable?) {
    if (!channel.isClosedForSend) ignoreTerminalEvents = true
    channel.close(cause)
    collectJob?.cancel()
  }

  override fun takeEvent(): Event<T> = channel.takeEvent(name = name)

  override fun takeItem(): T = channel.takeItem(name = name)

  override fun takeComplete() = channel.takeComplete(name = name)

  override fun takeError(): Throwable = channel.takeError(name = name)

  private var ignoreTerminalEvents = false
  private var ignoreRemainingEvents = false

  override suspend fun cancelAndIgnoreRemainingEvents() {
    cancel()
    ignoreRemainingEvents = true
  }

  override suspend fun cancelAndConsumeRemainingEvents(): List<Event<T>> {
    val events = buildList {
      while (true) {
        val event = channel.takeEventUnsafe() ?: break
        add(event)
        if (event is Event.Error || event is Event.Complete) break
      }
    }
    ignoreRemainingEvents = true
    cancel()

    return events
  }

  override fun expectNoEvents() {
    channel.expectNoEvents(name = name)
  }

  override fun expectMostRecentItem(): T = channel.expectMostRecentItem(name = name)

  override suspend fun awaitEvent(): Event<T> = withTurbineTimeout { channel.awaitEvent(name = name) }

  override suspend fun awaitItem(): T = withTurbineTimeout { channel.awaitItem(name = name) }

  override suspend fun skipItems(count: Int) = withTurbineTimeout { channel.skipItems(count, name) }

  override suspend fun awaitComplete() = withTurbineTimeout { channel.awaitComplete(name = name) }

  override suspend fun awaitError(): Throwable = withTurbineTimeout { channel.awaitError(name = name) }

  internal fun reportUnconsumedEvents(): UnconsumedEventReport<T> {
    if (ignoreRemainingEvents) return UnconsumedEventReport(emptyList())

    val unconsumed = mutableListOf<Event<T>>()
    var cause: Throwable? = null
    while (true) {
      val event = channel.takeEventUnsafe() ?: break
      if (event is Event.Error && event.throwable is CancellationException) break
      if (!(ignoreTerminalEvents && event.isTerminal)) unconsumed += event
      if (event is Event.Error) {
        cause = event.throwable
        break
      } else if (event is Event.Complete) {
        break
      }
    }

    return UnconsumedEventReport(
      name = name,
      unconsumed = unconsumed,
      cause = cause,
    )
  }

  override fun ensureAllEventsConsumed() {
    val report = reportUnconsumedEvents()

    if (report.unconsumed.isNotEmpty()) {
      throw TurbineAssertionError(
        buildString {
          report.describe(this)
        },
        report.cause,
      )
    }
  }
}

internal class UnconsumedEventReport<T>(
  val unconsumed: List<Event<T>>,
  val name: String? = null,
  val cause: Throwable? = null,
) {
  fun describe(builder: StringBuilder) {
    with(builder) {
      append("Unconsumed events found".qualifiedBy(name))
      append(":")
      for (event in unconsumed) {
        append("\n - $event")
      }
    }
  }

  fun describeException(builder: StringBuilder) {
    with(builder) {
      cause?.let { cause ->
        append("Unconsumed exception found".qualifiedBy(name))
        append(":")
        appendLine(
          """
            |
            |
            |Stack trace:
          """.trimMargin(),
        )
        append(cause.stackTraceToString())
        appendLine()
      }
    }
  }

  fun stripCancellations(): UnconsumedEventReport<T> =
    UnconsumedEventReport(
      unconsumed = unconsumed.filter {
        (it as? Event.Error)?.throwable !is CancellationException
      },
      name = name,
      cause = cause?.takeUnless { it is CancellationException },
    )
}
