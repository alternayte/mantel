package com.mantel.features.media

/**
 * The media lifecycle as a pure function. No I/O, no framework, and the `when` is exhaustive, so
 * the compiler refuses a new state or event that nobody handled. SDD.md 3.3 keeps this much of the
 * event-sourced style and nothing else: the result is written to a column.
 */
enum class ItemState {
    PENDING_UPLOAD,
    UPLOADED,
    PROCESSING,
    READY,
    FAILED,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(value: String): ItemState = entries.firstOrNull { it.wire == value } ?: error("unknown item state: $value")
    }
}

sealed interface ItemEvent {
    /** The bytes are in object storage. */
    data object UploadObserved : ItemEvent

    /** A worker took the item off the queue. */
    data object Claimed : ItemEvent

    /** Every derivative is written. */
    data object DerivativesWritten : ItemEvent

    /** The attempt failed and no attempts remain. */
    data class Exhausted(val error: String) : ItemEvent

    /** The attempt failed and the item goes back to the queue. */
    data object Requeued : ItemEvent

    /** The claim timed out, so the worker holding it is presumed gone. */
    data object ClaimExpired : ItemEvent

    /** A creator asked for a failed item to be tried again. */
    data object RetryRequested : ItemEvent
}

class IllegalTransition(val state: ItemState, val event: ItemEvent) :
    IllegalStateException("$event is not legal in $state")

fun transition(
    state: ItemState,
    event: ItemEvent,
): ItemState =
    when (state) {
        ItemState.PENDING_UPLOAD ->
            when (event) {
                ItemEvent.UploadObserved -> ItemState.UPLOADED
                else -> throw IllegalTransition(state, event)
            }

        ItemState.UPLOADED ->
            when (event) {
                ItemEvent.Claimed -> ItemState.PROCESSING
                else -> throw IllegalTransition(state, event)
            }

        ItemState.PROCESSING ->
            when (event) {
                ItemEvent.DerivativesWritten -> ItemState.READY
                is ItemEvent.Exhausted -> ItemState.FAILED
                ItemEvent.Requeued, ItemEvent.ClaimExpired -> ItemState.UPLOADED
                else -> throw IllegalTransition(state, event)
            }

        // A ready item is finished. Replacing a photo is a new item, not a new attempt.
        ItemState.READY -> throw IllegalTransition(state, event)

        ItemState.FAILED ->
            when (event) {
                ItemEvent.RetryRequested -> ItemState.UPLOADED
                else -> throw IllegalTransition(state, event)
            }
    }
