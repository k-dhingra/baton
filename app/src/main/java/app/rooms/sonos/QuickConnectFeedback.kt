package app.rooms.sonos

import kotlinx.coroutines.CancellationException

/** Retained UI state: completion is cleared only when the user dismisses it. */
data class QuickConnectFeedback(val roomName: String, val outcome: QuickConnectOutcome? = null) {
    val running: Boolean get() = outcome == null
    val title: String get() = when (outcome) {
        null -> "Reconnecting TV audio…"
        is QuickConnectOutcome.Selected -> "TV input selected"
        is QuickConnectOutcome.Failed -> "Could not reconnect"
    }
    val message: String get() = when (outcome) {
        null -> "Finding the device, selecting TV input and checking its response."
        is QuickConnectOutcome.Selected -> "The device confirmed TV input. If there is still no sound, check the source device’s audio output and cable connection. Baton cannot reset an HDMI handshake."
        is QuickConnectOutcome.Failed -> outcome.message
    }
}

suspend fun quickConnectWithFeedback(
    room: SonosDevice,
    report: (QuickConnectFeedback) -> Unit,
    connect: suspend () -> QuickConnectOutcome,
) {
    val name = room.roomName.ifBlank { "Selected device" }
    report(QuickConnectFeedback(name))
    val outcome = try {
        connect()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        QuickConnectOutcome.Failed("The device did not complete the request. Check Wi-Fi and try again.")
    }
    report(QuickConnectFeedback(name, outcome))
}
