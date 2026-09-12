package app.rooms.sonos

// Synthetic LAN fixtures only; no observed household topology is used.

import org.junit.Assert.*
import org.junit.Test

class SonosDailyDriverTest {
    @Test fun `parses escaped now playing metadata and position`() {
        val state = SonosProtocol.parseNowPlaying("""
            <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"><item>
            <dc:title>Rock &amp; Roll</dc:title><dc:creator>Artist</dc:creator>
            <upnp:album xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">Album</upnp:album>
            <res duration="00:03:12">x-file-cifs://track</res></item></DIDL-Lite>
        """.trimIndent(), "00:01:02")!!
        assertEquals("Rock & Roll", state.title)
        assertEquals("Artist", state.artist)
        assertEquals(62, state.positionSeconds)
        assertEquals(192, state.durationSeconds)
        assertEquals("x-file-cifs://track", state.trackUri)
    }

    @Test fun `parses mute and equalizer values`() {
        assertEquals(true, SonosProtocol.parseBooleanValue("<CurrentMute>1</CurrentMute>", "CurrentMute"))
        assertEquals(false, SonosProtocol.parseBooleanValue("<CurrentLoudness>0</CurrentLoudness>", "CurrentLoudness"))
        assertEquals(-3, SonosProtocol.parseIntValue("<CurrentBass>-3</CurrentBass>", "CurrentBass"))
    }

    @Test fun `rejects remote artwork but permits local sonos artwork`() {
        assertTrue(SonosProtocol.isSafeArtworkUri("http://10.23.45.8:1400/album.jpg"))
        assertFalse(SonosProtocol.isSafeArtworkUri("https://example.com/album.jpg"))
        assertFalse(SonosProtocol.isSafeArtworkUri("file:///etc/passwd"))
    }

    @Test fun `parses queue and favourite entries without inventing metadata`() {
        val entries = SonosProtocol.parseDidlItems("""
          <DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="Q:0/1"><dc:title>One</dc:title><res>uri:one</res></item>
          <item id="FV:2/2"><dc:title>Saved</dc:title></item></DIDL-Lite>
        """)
        assertEquals("One", entries[0].title); assertEquals("uri:one", entries[0].uri)
        assertEquals("Saved", entries[1].title); assertEquals(null, entries[1].uri)
    }

    @Test fun `model number is retained without downgrading model name`() {
        val device = SonosProtocol.deviceFromDescription("""
          <root><device><manufacturer>Sonos, Inc.</manufacturer>
          <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
          <UDN>uuid:X</UDN><roomName>Room</roomName><modelName>Sonos Arc Ultra</modelName><modelNumber>S38</modelNumber>
          </device></root>
        """, "10.23.45.8")!!
        assertEquals("Sonos Arc Ultra", device.modelName); assertEquals("S38", device.modelNumber)
    }

    @Test fun `group plan uses coordinator URI and standalone unjoin`() {
        assertEquals("x-rincon:COORD", SonosProtocol.groupJoinUri("COORD"))
        assertEquals("BecomeCoordinatorOfStandaloneGroup", SonosProtocol.groupLeaveAction)
    }
}
