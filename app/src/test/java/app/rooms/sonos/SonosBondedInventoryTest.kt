package app.rooms.sonos

// Synthetic LAN fixtures only; no observed household topology is used.

import org.junit.Assert.*
import org.junit.Test

class SonosBondedInventoryTest {
    private val topology = """
        <ZoneGroups><ZoneGroup Coordinator="ARC">
          <ZoneGroupMember UUID="ARC" ZoneName="Sample Room" ModelName="Sonos Arc"
            HTSatChanMapSet="ARC:LF,RF;LEFT:LR,LTR;RIGHT:RR,RTR;SUB:SW" />
        </ZoneGroup></ZoneGroups>
    """.trimIndent()

    @Test fun `bonded channels synthesize two Era 100 surrounds and full Sub`() {
        val products = SonosProtocol.inventoryProducts(
            listOf(SonosDevice("ARC", "Sample Room", "Sonos Arc", "10.23.45.8")), topology
        )
        assertEquals(setOf("ARC", "LEFT", "RIGHT", "SUB"), products.map { it.device.uid }.toSet())
        assertEquals(2, products.count { it.role == SonosProductRole.SURROUND && it.device.modelName == "Sonos Era 100" })
        assertEquals(1, products.count { it.role == SonosProductRole.SUB && it.device.modelName == "Sonos Sub" })
    }

    @Test fun `synthesized products are inferred and unroutable`() {
        val inferred = SonosProtocol.inventoryProducts(emptyList(), topology).filter { it.inferred }
        assertTrue(inferred.isNotEmpty())
        assertTrue(inferred.all { it.device.ip.isBlank() && !it.isRoutable })
    }

    @Test fun `network description overrides inference by UID`() {
        val exact = SonosDevice("LEFT", "Sample Room", "Era 100", "10.23.45.9")
        val products = SonosProtocol.inventoryProducts(listOf(exact), topology)
        assertEquals(1, products.count { it.device.uid == "LEFT" })
        assertFalse(products.first { it.device.uid == "LEFT" }.inferred)
        assertEquals("10.23.45.9", products.first { it.device.uid == "LEFT" }.device.ip)
    }

    @Test fun `malformed bonded mapping is ignored`() {
        val xml = topology.replace("ARC:LF,RF;LEFT:LR,LTR;RIGHT:RR,RTR;SUB:SW", "broken;:SW;SUB:not-a-channel")
        assertEquals(1, SonosProtocol.inventoryProducts(listOf(SonosDevice("ARC", "Sample Room", "Sonos Arc", "10.23.45.8")), xml).size)
    }
}
