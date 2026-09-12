package app.rooms.sonos

// Synthetic LAN fixtures only; no observed household topology is used.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QuickConnectFeedbackTest {
    @Test fun `default copy describes devices without a household brand`() {
        val running = QuickConnectFeedback("Device")
        val selected = QuickConnectFeedback("Device", QuickConnectOutcome.Selected(SonosTheatre("Room", "arc", emptySet(), null)))
        assertTrue(running.message.contains("device"))
        assertTrue(selected.message.contains("device"))
        assertFalse(selected.message.contains("soundbar", true))
    }

    @Test fun `unnamed recovery target uses selected device`() = runBlocking {
        val states = mutableListOf<QuickConnectFeedback>()
        quickConnectWithFeedback(SonosDevice("arc", "", "Sonos Arc", "10.23.45.8"), { states += it }) {
            QuickConnectOutcome.Failed("Unavailable")
        }
        assertEquals("Selected device", states.first().roomName)
    }
    private val room = SonosDevice("arc", "Sample Room", "Sonos Arc", "10.23.45.8")
    @Test fun `feedback opens before IO and completed failure remains visible`() = runBlocking {
        val states = mutableListOf<QuickConnectFeedback>()
        quickConnectWithFeedback(room, { states += it }) {
            assertEquals(1, states.size)
            assertTrue(states.single().running)
            QuickConnectOutcome.Failed("The selected device is unavailable")
        }
        assertEquals(2, states.size)
        assertFalse(states.last().running)
        assertEquals("Could not reconnect", states.last().title)
        assertEquals("The selected device is unavailable", states.last().message)
    }
    @Test fun `instant success still produces a retained result and honest input claim`() = runBlocking {
        val states = mutableListOf<QuickConnectFeedback>()
        quickConnectWithFeedback(room, { states += it }) {
            QuickConnectOutcome.Selected(SonosTheatre("Sample Room", "arc", emptySet(), null))
        }
        assertEquals(2, states.size)
        assertEquals("TV input selected", states.last().title)
        assertTrue(states.last().message.contains("HDMI"))
        assertFalse(states.last().message.contains("fixed", true))
    }
    @Test fun `exception produces a visible safe error not a disappearing flash`() = runBlocking {
        val states = mutableListOf<QuickConnectFeedback>()
        quickConnectWithFeedback(room, { states += it }) { throw java.io.IOException("private endpoint") }
        assertEquals(2, states.size)
        assertFalse(states.last().running)
        assertTrue(states.last().outcome is QuickConnectOutcome.Failed)
        assertFalse(states.last().message.contains("private endpoint"))
    }
    @Test fun `cancellation propagates instead of reporting success`() = runBlocking {
        try {
            quickConnectWithFeedback(room, {}) { throw CancellationException("cancelled") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
