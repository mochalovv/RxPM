@file:Suppress("NOTHING_TO_INLINE")

package me.dmdev.rxpm

import com.jakewharton.rxrelay2.Relay
import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.BiFunction
import io.reactivex.functions.Consumer
import io.reactivex.functions.Function

/**
 * @author Dmitriy Gorbunov
 */

/**
 * Convenience to get this [Relay] as an [Observable].
 * Helps to ensure code readability.
 */
inline fun <T> Relay<T>.asObservable(): Observable<T> {
    return this.hide()
}

/**
 * Convenience to get this [Relay] as an [Consumer].
 * Helps to ensure code readability.
 */
inline fun <T> Relay<T>.asConsumer(): Consumer<T> {
    return this
}

/**
 * Convenience to bind the [progress][progressConsumer] to the [Single].
 */
inline fun <T> Single<T>.bindProgress(progressConsumer: Consumer<Boolean>): Single<T> {
    return this
            .doOnSubscribe { progressConsumer.accept(true) }
            .doOnSuccess { progressConsumer.accept(false) }
            .doOnError { progressConsumer.accept(false) }
}

/**
 * Convenience to bind the [progress][progressConsumer] to the [Completable].
 */
inline fun Completable.bindProgress(progressConsumer: Consumer<Boolean>): Completable {
    return this
            .doOnSubscribe { progressConsumer.accept(true) }
            .doOnTerminate { progressConsumer.accept(false) }
}

/**
 * Convenience to filter out items emitted by the source [Observable] when in progress ([progressState] last value is `true`).
 */
inline fun <T> Observable<T>.skipWhileInProgress(progressState: Observable<Boolean>): Observable<T> {
    return this.withLatestFrom(progressState.startWith(false),
                               BiFunction<T, Boolean, Pair<T, Boolean>> { t, progress ->
                                   Pair(t, progress)
                               })
            .filter { !it.second }
            .map { it.first }
}

/**
 * Returns the [Observable] that emits items when active, and buffers them when [idle][isIdle].
 * Buffered items is emitted when idle state ends.
 * @param isIdle shows when the idle state begins (`true`) and ends (`false`).
 * @param bufferSize number of items the buffer can hold. `null` means not constrained.
 */
inline fun <T> Observable<T>.bufferWhileIdle(isIdle: Observable<Boolean>, bufferSize: Int? = null): Observable<T> {

    return Observable
            .merge(
                    this.withLatestFrom(isIdle,
                                        BiFunction<T, Boolean, Pair<T, Boolean>> { t, idle ->
                                            Pair(t, idle)
                                        })
                            .filter { !it.second }
                            .map { it.first },

                    this
                            .buffer(
                                    isIdle.filter { it },
                                    Function<Boolean, Observable<Boolean>> {
                                        isIdle.filter { !it }
                                    })
                            .map {
                                if (bufferSize != null) it.takeLast(bufferSize)
                                else it
                            }
                            .flatMapIterable { it }

            )
            .publish()
            .apply { connect() }
}
