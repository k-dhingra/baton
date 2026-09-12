package app.rooms.sonos

// Synthetic LAN fixtures only; no observed household topology is used.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosProtocolTest {
    @Test
    fun `SSDP location header is case insensitive`() {
        val packet = "HTTP/1.1 200 OK\r\nLoCaTiOn: http://10.23.45.8:1400/xml/device_description.xml\r\n\r\n"
        assertEquals("http://10.23.45.8:1400/xml/device_description.xml", SonosProtocol.locationFromSsdp(packet))
    }

    @Test
    fun `device description exposes room model and uid only for Sonos zone players`() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <deviceType>urn:schemas-upnp-org:device:ZonePlayer:1</deviceType>
              <manufacturer>Sonos, Inc.</manufacturer>
              <roomName>Sample Room</roomName><modelName>Sonos Arc</modelName>
              <UDN>uuid:SYNTH_COORDINATOR</UDN>
            </device></root>
        """.trimIndent()
        assertEquals(
            SonosDevice("SYNTH_COORDINATOR", "Sample Room", "Sonos Arc", "10.23.45.8"),
            SonosProtocol.deviceFromDescription(xml, "10.23.45.8"),
        )
        assertEquals(null, SonosProtocol.deviceFromDescription(xml.replace("Sonos, Inc.", "Impostor"), "10.23.45.8"))
    }

    @Test
    fun `TV recovery is shown only for home theatre products`() {
        assertTrue(SonosProtocol.supportsTvAudio("Sonos Arc"))
        assertTrue(SonosProtocol.supportsTvAudio("Beam (Gen 2)"))
        assertFalse(SonosProtocol.supportsTvAudio("Era 100"))
        assertFalse(SonosProtocol.supportsTvAudio("Sub Mini"))
    }

    @Test
    fun `home theatre map separates surrounds and sub`() {
        val state = """
            <ZoneGroups><ZoneGroup Coordinator="SYNTH_COORDINATOR">
              <ZoneGroupMember UUID="SYNTH_COORDINATOR" ZoneName="Sample Room" ModelName="Sonos Arc"
                HTSatChanMapSet="SYNTH_COORDINATOR:LF,RF;SYNTH_SURROUND_LEFT:LR,LTR;SYNTH_SURROUND_RIGHT:RR,RTR;SYNTH_SUB:SW" />
            </ZoneGroup></ZoneGroups>
        """.trimIndent()
        val theatre = SonosProtocol.theatreFromZoneState(state, "SYNTH_COORDINATOR")!!
        assertEquals("Sample Room", theatre.roomName)
        assertEquals(setOf("SYNTH_SURROUND_LEFT", "SYNTH_SURROUND_RIGHT"), theatre.surroundUids)
        assertEquals("SYNTH_SUB", theatre.subUid)
    }

    @Test
    fun `TV source is recognised and recovery URI targets the coordinator`() {
        assertTrue(SonosProtocol.isTvSource("x-sonos-htastream:SYNTH_COORDINATOR:spdif"))
        assertFalse(SonosProtocol.isTvSource("x-rincon-queue:SYNTH_COORDINATOR#0"))
        assertEquals("x-sonos-htastream:SYNTH_COORDINATOR:spdif", SonosProtocol.tvUri("SYNTH_COORDINATOR"))
    }

    @Test
    fun `SOAP zone state is unwrapped before topology parsing`() {
        val response = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
              <u:GetZoneGroupStateResponse xmlns:u="urn:schemas-upnp-org:service:ZoneGroupTopology:1">
                <ZoneGroupState>&lt;ZoneGroups&gt;&lt;ZoneGroup Coordinator="SYNTH_COORDINATOR" /&gt;&lt;/ZoneGroups&gt;</ZoneGroupState>
              </u:GetZoneGroupStateResponse>
            </s:Body></s:Envelope>
        """.trimIndent()
        val state = SonosProtocol.zoneStateFromSoap(response)!!
        assertTrue(state.startsWith("<ZoneGroups>"))
        assertEquals(setOf("SYNTH_COORDINATOR"), SonosProtocol.coordinatorUids(state))
    }

    @Test
    fun `service paths match Sonos UPnP endpoints`() {
        assertEquals("/MediaRenderer/AVTransport/Control", SonosProtocol.servicePath("AVTransport"))
        assertEquals("/MediaRenderer/RenderingControl/Control", SonosProtocol.servicePath("RenderingControl"))
        assertEquals("/ZoneGroupTopology/Control", SonosProtocol.servicePath("ZoneGroupTopology"))
    }

    @Test
    fun `SSDP search uses real CRLF delimiters`() {
        val query = SonosProtocol.ssdpSearchQuery("urn:schemas-upnp-org:device:ZonePlayer:1")
        assertTrue(query.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(query.endsWith("\r\n\r\n"))
        assertFalse(query.contains("\\r\\n"))
    }

    @Test
    fun `discovery locations must be local Sonos HTTP endpoints`() {
        assertTrue(SonosProtocol.isLocalSonosLocation("http://10.23.44.8:1400/xml/device_description.xml"))
        assertFalse(SonosProtocol.isLocalSonosLocation("https://10.23.44.8:1400/xml/device_description.xml"))
        assertFalse(SonosProtocol.isLocalSonosLocation("http://8.8.8.8:1400/xml/device_description.xml"))
        assertFalse(SonosProtocol.isLocalSonosLocation("http://127.0.0.1:1400/xml/device_description.xml"))
        assertFalse(SonosProtocol.isLocalSonosLocation("http://10.23.44.8:80/xml/device_description.xml"))
    }

    @Test
    fun `source labels hide Sonos transport URIs`() {
        assertEquals("TV", SonosProtocol.sourceLabel("x-sonos-htastream:SYNTH_COORDINATOR:spdif"))
        assertEquals("Queue", SonosProtocol.sourceLabel("x-rincon-queue:SYNTH_COORDINATOR#0"))
        assertEquals("AirPlay", SonosProtocol.sourceLabel("x-sonos-vli:SYNTH_COORDINATOR:airplay"))
        assertEquals("Idle", SonosProtocol.sourceLabel(""))
    }

    @Test
    fun `visible rooms include grouped members but exclude bonded satellites`() {
        val state = """
            <ZoneGroups><ZoneGroup Coordinator="SYNTH_COORDINATOR">
              <ZoneGroupMember UUID="SYNTH_COORDINATOR" Invisible="0" HTSatChanMapSet="SYNTH_COORDINATOR:LF,RF;SYNTH_SURROUND_LEFT:LR;SYNTH_SUB:SW" />
              <ZoneGroupMember UUID="SYNTH_SECONDARY" Invisible="0" />
              <ZoneGroupMember UUID="SYNTH_SURROUND_LEFT" Invisible="1" />
              <ZoneGroupMember UUID="SYNTH_SUB" Invisible="1" />
            </ZoneGroup></ZoneGroups>
        """.trimIndent()
        assertEquals(setOf("SYNTH_COORDINATOR", "SYNTH_SECONDARY"), SonosProtocol.visibleRoomUids(state))
    }

    @Test
    fun `discovery diagnostics identify the failed stage`() {
        assertEquals("No Sonos replies · multicast and local scan tried", SonosProtocol.discoveryMessage(0, 0))
        assertEquals("3 local replies · none identified as Sonos", SonosProtocol.discoveryMessage(3, 0))
        assertEquals("2 Sonos rooms found", SonosProtocol.discoveryMessage(3, 2))
        assertEquals(
            "No rooms · multicast blocked (SecurityException) · local scan found none",
            SonosProtocol.discoveryMessage(0, 0, "SecurityException", null),
        )
        assertEquals(
            "No rooms · local scan failed (SecurityException)",
            SonosProtocol.discoveryMessage(0, 0, null, "SecurityException"),
        )
    }

    @Test
    fun `local discovery fallback uses the actual bounded prefix`() {
        val hosts = SonosProtocol.localScanHosts("10.23.45.42", 24)
        assertTrue("10.23.45.1" in hosts)
        assertTrue("10.23.45.254" in hosts)
        assertFalse("10.23.45.42" in hosts)
        assertFalse("10.24.1.1" in hosts)

        val slash23 = SonosProtocol.localScanHosts("10.23.45.42", 23)
        assertTrue("10.23.44.1" in slash23)
        assertTrue("10.23.45.254" in slash23)
        assertEquals(emptyList<String>(), SonosProtocol.localScanHosts("10.23.45.42", 16))
    }

    @Test
    fun `device XML with declarations is rejected before parsing`() {
        val malicious = "<!DOCTYPE root [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><root><device /></root>"
        assertEquals(null, SonosProtocol.deviceFromDescription(malicious, "10.23.45.8"))
    }
}
