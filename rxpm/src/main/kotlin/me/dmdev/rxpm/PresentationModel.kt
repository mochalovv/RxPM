package me.dmdev.rxpm

import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.functions.Consumer
import me.dmdev.rxpm.navigation.NavigationMessage
import me.dmdev.rxpm.navigation.NavigationMessageHandler
import java.util.concurrent.atomic.AtomicReference

/**
 * Parent class for any Presentation Model.
 */
abstract class PresentationModel {

    enum class Lifecycle {
        CREATED, BINDED, UNBINDED, DESTROYED
    }

    private val compositeDestroy = CompositeDisposable()
    private val compositeUnbind = CompositeDisposable()

    private val lifecycle = BehaviorRelay.create<Lifecycle>()
    private val unbind = BehaviorRelay.createDefault<Boolean>(true)

    /**
     * Command to send [navigation message][NavigationMessage] to the [NavigationMessageHandler].
     * @since 1.1
     */
    val navigationMessages = Command<NavigationMessage>()

    /**
     * The [lifecycle][Lifecycle] state of this presentation model.
     */
    val lifecycleObservable = lifecycle.asObservable()
    internal val lifecycleConsumer = lifecycle.asConsumer()

    init {
        lifecycle
                .takeUntil { it == Lifecycle.DESTROYED }
                .subscribe {
                    @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
                    when (it) {
                        Lifecycle.CREATED -> onCreate()
                        Lifecycle.BINDED -> onBind()
                        Lifecycle.UNBINDED -> {
                            compositeUnbind.clear()
                            onUnbind()
                        }
                        Lifecycle.DESTROYED -> {
                            compositeDestroy.clear()
                            onDestroy()
                        }
                    }
                }

        lifecycle
                .takeUntil { it == Lifecycle.DESTROYED }
                .subscribe {
                    when (it) {
                        Lifecycle.BINDED -> unbind.accept(false)
                        Lifecycle.UNBINDED -> unbind.accept(true)
                        else -> {}
                    }
                }
    }

    /**
     * Called when the presentation model is created.
     * @see [onBind]
     * @see [onUnbind]
     * @see [onDestroy]
     */
    protected open fun onCreate() {}

    /**
     * Called when the presentation model binds to the [view][PmView].
     * @see [onCreate]
     * @see [onUnbind]
     * @see [onDestroy]
     */
    protected open fun onBind() {}

    /**
     * Called when the presentation model unbinds from the [view][PmView].
     * @see [onCreate]
     * @see [onBind]
     * @see [onDestroy]
     */
    protected open fun onUnbind() {}

    /**
     * Called just before the presentation model will be destroyed.
     * @see [onCreate]]
     * @see [onBind]
     * @see [onUnbind]
     */
    protected open fun onDestroy() {}

    /**
     * Local extension to add this [Disposable] to the [CompositeDisposable][compositeUnbind]
     * that will be CLEARED ON [UNBIND][Lifecycle.UNBINDED].
     */
    protected fun Disposable.untilUnbind() {
        compositeUnbind.add(this)
    }

    /**
     * Local extension to add this [Disposable] to the [CompositeDisposable][compositeDestroy]
     * that will be CLEARED ON [DESTROY][Lifecycle.DESTROYED].
     */
    protected fun Disposable.untilDestroy() {
        compositeDestroy.add(this)
    }

    /**
     * Binds `this` [PresentationModel]'s (child) lifecycle to the enclosing presentation model's (parent) lifecycle.
     */
    protected fun PresentationModel.bindLifecycle() {
        this@PresentationModel.lifecycle
                .subscribe(this.lifecycleConsumer)
                .untilDestroy()
    }

    /**
     * Returns the [Observable] that emits items when active, and buffers them when [unbinded][Lifecycle.UNBINDED].
     * Buffered items is emitted when this presentation model binds to the [view][PmView].
     * @param bufferSize number of items the buffer can hold. `null` means not constrained.
     */
    protected fun <T> Observable<T>.bufferWhileUnbind(bufferSize: Int? = null): Observable<T> {
        return this.bufferWhileIdle(unbind, bufferSize)
    }

    /**
     * Consumer of the [State].
     * Accessible only from a [PresentationModel].
     *
     * Use to subscribe the state to some [Observable] source.
     */
    protected val <T> State<T>.consumer: Consumer<T> get() = relay

    /**
     * Observable of the [Action].
     * Accessible only from a [PresentationModel].
     *
     * Use to subscribe to this [Action]s source.
     */
    protected val <T> Action<T>.observable: Observable<T> get() = relay

    /**
     * Consumer of the [Command].
     * Accessible only from a [PresentationModel].
     *
     * Use to subscribe the command to some [Observable] source.
     */
    protected val <T> Command<T>.consumer: Consumer<T> get() = relay

    /**
     * Reactive property for the [view's][PmView] state.
     * Can be observed and changed in reactive manner with it's [observable] and [consumer].
     *
     * Use to represent a view state. It can be something simple, like some widget's text, or complex,
     * like inProgress or data.
     *
     * @see Action
     * @see Command
     */
    inner class State<T>(initialValue: T? = null) {
        internal val relay =
                if (initialValue != null) {
                    BehaviorRelay.createDefault<T>(initialValue).toSerialized()
                } else {
                    BehaviorRelay.create<T>().toSerialized()
                }

        private val cachedValue =
                if (initialValue != null) {
                    AtomicReference<T?>(initialValue)
                } else {
                    AtomicReference()
                }

        /**
         * Observable of this [State].
         */
        val observable = relay.asObservable()

        /**
         * Returns a current value.
         * @throws UninitializedPropertyAccessException if there is no value and [State] was created without `initialValue`.
         */
        val value: T
            get() {
                return cachedValue.get() ?: throw UninitializedPropertyAccessException(
                        "The State has no value yet. Use valueOrNull() or pass initialValue to the constructor.")
            }

        /**
         * Returns a current value or null.
         */
        val valueOrNull: T? get() = cachedValue.get()

        init {
            relay.subscribe { cachedValue.set(it) }
        }

        /**
         * Returns true if the [State] has any value.
         */
        fun hasValue() = cachedValue.get() != null
    }

    /**
     * Reactive property for the actions from the [view][PmView].
     * Can be changed and observed in reactive manner with it's [consumer] and [observable].
     *
     * Use to send actions of the view, e.g. some widget's clicks.
     *
     * @see State
     * @see Command
     */
    inner class Action<T> {
        internal val relay = PublishRelay.create<T>().toSerialized()

        /**
         * Consumer of the [Action][Action].
         */
        val consumer get() = relay.asConsumer()
    }

    /**
     * Reactive property for the commands to the [view][PmView].
     * Can be observed and changed in reactive manner with it's [observable] and [consumer].
     *
     * Use to represent a command to the view, e.g. toast or dialog showing.
     *
     * @param isIdle observable, that shows when `command` need to buffer the values (while isIdle value is true).
     * Buffered values will be delivered later (when isIdle emits false).
     * By default (when null is passed) it will buffer while the [view][PmView] is unbind from the [PresentationModel].
     *
     * @param bufferSize how many values should be kept in buffer. Null means no restrictions.
     *
     * @see Action
     * @see Command
     */
    inner class Command<T>(isIdle: Observable<Boolean>? = null,
                           bufferSize: Int? = null) {
        internal val relay = PublishRelay.create<T>().toSerialized()

        /**
         * Observable of this [Command].
         */
        val observable =
                if (bufferSize == 0) {
                    relay.asObservable()
                } else {
                    if (isIdle == null) {
                        relay.bufferWhileUnbind(bufferSize)
                    } else {
                        relay.bufferWhileIdle(isIdle, bufferSize)
                    }
                }
    }

}

