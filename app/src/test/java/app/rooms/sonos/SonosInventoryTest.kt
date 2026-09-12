package app.rooms.sonos

// Synthetic LAN fixtures only; no observed household topology is used.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosInventoryTest {
    @Test fun `catalogue recognizes required products and preserves unknown label`() {
        assertEquals(SonosProductKind.ARC_ULTRA, SonosProductKind.fromModel("Sonos Arc Ultra"))
        assertEquals(SonosProductKind.BEAM, SonosProductKind.fromModel("Beam (Gen 2)"))
        assertEquals(SonosProductKind.ERA_100, SonosProductKind.fromModel("Sonos Era 100"))
        assertEquals(SonosProductKind.SUB, SonosProductKind.fromModel("Sonos Sub"))
        assertEquals(SonosProductKind.SUB_MINI, SonosProductKind.fromModel("Sub Mini"))
        assertEquals(SonosProductKind.UNKNOWN, SonosProductKind.fromModel("Sonos Future X"))
    }

    @Test fun `system snapshot separates coordinators from every physical product`() {
        val arc = SonosDevice("arc", "Sample Room", "Sonos Arc", "10.23.45.8")
        val sub = SonosDevice("sub", "Sample Room", "Sonos Sub", "10.23.45.9")
        val system = SonosSystem.from(listOf(arc, sub), setOf("arc"))
        assertEquals(listOf(arc), system.rooms)
        assertEquals(listOf(arc, sub), system.products.map { it.device })
        assertTrue(system.products.first { it.device.uid == "sub" }.isRoomCoordinator.not())
    }

    @Test fun `viewer helpers clamp zoom and project finite points`() {
        assertEquals(1f, ViewerMath.clampZoom(0.1f), 0f)
        assertEquals(3f, ViewerMath.clampZoom(9f), 0f)
        val point = ViewerMath.project(ViewerPoint(1f, 1f, 1f), 0.6f, 0.3f, 1.2f)
        assertTrue(point.x.isFinite() && point.y.isFinite())
    }
}
