package com.mantel.features.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Every legal and illegal transition, with no database and no framework (SDD.md 5.1).
 */
class ItemStateTest {
    private val allEvents =
        listOf(
            ItemEvent.UploadObserved,
            ItemEvent.Claimed,
            ItemEvent.DerivativesWritten,
            ItemEvent.Exhausted("boom"),
            ItemEvent.Requeued,
            ItemEvent.ClaimExpired,
            ItemEvent.RetryRequested,
        )

    private val legal =
        mapOf(
            (ItemState.PENDING_UPLOAD to ItemEvent.UploadObserved) to ItemState.UPLOADED,
            (ItemState.UPLOADED to ItemEvent.Claimed) to ItemState.PROCESSING,
            (ItemState.PROCESSING to ItemEvent.DerivativesWritten) to ItemState.READY,
            (ItemState.PROCESSING to ItemEvent.Exhausted("boom")) to ItemState.FAILED,
            (ItemState.PROCESSING to ItemEvent.Requeued) to ItemState.UPLOADED,
            (ItemState.PROCESSING to ItemEvent.ClaimExpired) to ItemState.UPLOADED,
            (ItemState.FAILED to ItemEvent.RetryRequested) to ItemState.UPLOADED,
        )

    @Test
    fun `every legal transition lands where the design says`() {
        legal.forEach { (from, expected) ->
            assertEquals(expected, transition(from.first, from.second), "${from.first} on ${from.second}")
        }
    }

    @Test
    fun `every other pairing is refused`() {
        ItemState.entries.forEach { state ->
            allEvents.forEach { event ->
                if ((state to event) !in legal) {
                    assertThrows(IllegalTransition::class.java, { transition(state, event) }, "$state on $event")
                }
            }
        }
    }

    @Test
    fun `a ready item is finished`() {
        allEvents.forEach { event ->
            assertThrows(IllegalTransition::class.java) { transition(ItemState.READY, event) }
        }
    }

    @Test
    fun `a crashed worker's item returns to the queue, not to failed`() {
        assertEquals(ItemState.UPLOADED, transition(ItemState.PROCESSING, ItemEvent.ClaimExpired))
    }

    @Test
    fun `the wire names are the ones the API and the database use`() {
        assertEquals(
            listOf("pending_upload", "uploaded", "processing", "ready", "failed"),
            ItemState.entries.map { it.wire },
        )
        ItemState.entries.forEach { assertEquals(it, ItemState.fromWire(it.wire)) }
    }
}
