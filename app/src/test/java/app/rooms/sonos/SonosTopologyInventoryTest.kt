package app.rooms.sonos

// Synthetic LAN fixtures only; no observed household topology is used.

import org.junit.Assert.assertEquals
import org.junit.Test

class SonosTopologyInventoryTest {
    @Test fun `topology failure picks one non-sub coordinator per room and keeps bonded products`() {
        val arc = SonosDevice("arc", "Sample Room", "Sonos Arc", "10.23.45.8")
        val era = SonosDevice("era", "Sample Room", "Era 100", "10.23.45.9")
        val sub = SonosDevice("sub", "Sample Room", "Sub Mini", "10.23.45.10")
        assertEquals(setOf("arc"), SonosProtocol.coordinatorUidsForInventory(listOf(era, sub, arc), null))
        assertEquals(3, SonosSystem.from(listOf(era, sub, arc), setOf("arc")).products.size)
    }

    @Test fun `topology product locations accept only local device endpoints`() {
        val xml = """
            <ZoneGroups><ZoneGroup><ZoneGroupMember UUID="SYNTH_COORDINATOR" Location="http://10.23.45.8:1400/xml/device_description.xml" />
            <Satellite Location="http://10.23.45.9:1400/xml/device_description.xml" />
            <Satellite Location="https://10.23.45.10:1400/xml/device_description.xml" />
            <Satellite Location="http://8.8.8.8:1400/xml/device_description.xml" /></ZoneGroup></ZoneGroups>
        """.trimIndent()
        assertEquals(setOf("http://10.23.45.8:1400/xml/device_description.xml", "http://10.23.45.9:1400/xml/device_description.xml"), SonosProtocol.productLocations(xml))
    }
}
