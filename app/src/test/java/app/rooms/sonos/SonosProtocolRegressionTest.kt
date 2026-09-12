package app.rooms.sonos

// Synthetic LAN fixtures only; no observed household topology is used.

import org.junit.Assert.*
import org.junit.Test

class SonosProtocolRegressionTest {
    @Test fun `escaped soap position metadata decodes through xml text`() {
        val response = "<Envelope><TrackMetaData>&lt;DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\"&gt;&lt;item&gt;&lt;dc:title&gt;A &amp;amp; B&lt;/dc:title&gt;&lt;upnp:albumArtURI xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">/getaa?u=1&lt;/upnp:albumArtURI&gt;&lt;res&gt;track&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</TrackMetaData><TrackURI>x-file</TrackURI><RelTime>00:01:02</RelTime><TrackDuration>00:03:00</TrackDuration></Envelope>"
        val state = SonosProtocol.nowPlayingFromPosition(response, "10.23.45.8")!!
        assertEquals("A & B", state.title)
        assertEquals("x-file", state.trackUri)
        assertEquals(62, state.positionSeconds)
        assertEquals(180, state.durationSeconds)
        assertEquals("http://10.23.45.8:1400/getaa?u=1", state.artworkUri)
    }

    @Test fun `bass treble loudness use exact rendering actions`() {
        assertEquals("GetBass", SonosProtocol.soundAction("bass", false))
        assertEquals("SetBass", SonosProtocol.soundAction("bass", true))
        assertEquals("<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredBass>-3</DesiredBass>", SonosProtocol.soundArguments("bass", -3))
        assertEquals("GetLoudness", SonosProtocol.soundAction("loudness", false))
        assertEquals("<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredLoudness>1</DesiredLoudness>", SonosProtocol.soundArguments("loudness", 1))
    }

    @Test fun `transport actions and duration are parsed`() {
        assertEquals(setOf("Play", "Pause", "Next"), SonosProtocol.transportActions("<CurrentTransportActions>Play,Pause,Next</CurrentTransportActions>"))
        assertEquals(3723, SonosProtocol.durationSeconds("01:02:03"))
        assertEquals(7, SonosProtocol.durationSeconds("00:07"))
    }

    @Test fun `artwork resolution only allows local port 1400`() {
        assertEquals("http://10.23.45.8:1400/getaa?u=1", SonosProtocol.resolveArtwork("/getaa?u=1", "10.23.45.8"))
        assertNull(SonosProtocol.resolveArtwork("https://example.com/a.jpg", "10.23.45.8"))
        assertNull(SonosProtocol.resolveArtwork("http://10.23.45.9:1401/a.jpg", "10.23.45.8"))
    }

    @Test fun `topology exposes current members and desired plan`() {
        val xml = "<ZoneGroups><ZoneGroup Coordinator=\"A\"><ZoneGroupMember UUID=\"A\" Location=\"http://10.23.45.10:1400/xml/device_description.xml\"/><ZoneGroupMember UUID=\"B\"/></ZoneGroup><ZoneGroup Coordinator=\"C\"><ZoneGroupMember UUID=\"C\"/></ZoneGroup></ZoneGroups>"
        assertEquals(setOf("A", "B"), SonosProtocol.groupMembers(xml, "A"))
        assertEquals("http://10.23.45.10:1400/xml/device_description.xml", SonosProtocol.coordinatorLocation(xml, "B"))
        assertEquals(listOf(SonosGroupAction.Join("B", "C"), SonosGroupAction.Leave("C")), SonosProtocol.groupPlan(xml, mapOf("B" to "C", "C" to null)))
    }

    @Test fun `upnp command fault keeps the device reason`() {
        val fault = "<s:Fault xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><detail><UPnPError><errorCode>701</errorCode><errorDescription>Transition not available</errorDescription></UPnPError></detail></s:Fault>"
        assertEquals("Transition not available (UPnP 701)", SonosProtocol.upnpCommandError(fault))
        assertNull(SonosProtocol.upnpCommandError("<Envelope/>"))
    }

    @Test fun `playback restriction is source and capability aware`() {
        assertEquals("TV audio is controlled by your television", SonosProtocol.playbackUnavailableReason("x-sonos-htastream:RINCON", emptySet()))
        assertEquals("This source has nothing resumable", SonosProtocol.playbackUnavailableReason("x-rincon-queue:RINCON#0", setOf("Next")))
        assertNull(SonosProtocol.playbackUnavailableReason("x-rincon-queue:RINCON#0", setOf("Play", "Next")))
        assertNull(SonosProtocol.playbackUnavailableReason("x-rincon-queue:RINCON#0", emptySet()))
    }
}
