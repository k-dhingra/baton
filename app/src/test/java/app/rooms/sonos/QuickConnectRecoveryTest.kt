package app.rooms.sonos

// Synthetic LAN fixtures only; no observed household topology is used.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickConnectRecoveryTest {
    @Test fun `preflight accepts only the stored eligible standalone soundbar`() {
        val target = SonosDevice("arc", "Sample Room", "Sonos Arc", "10.23.45.8")
        val topology = """
            <ZoneGroups><ZoneGroup Coordinator="arc">
              <ZoneGroupMember UUID="arc" ZoneName="Sample Room" HTSatChanMapSet="arc:LF,RF;left:LR;sub:SW" />
            </ZoneGroup></ZoneGroups>
        """.trimIndent()
        assertEquals(
            QuickConnectPreflight.Ready(setOf("arc", "left", "sub")),
            SonosProtocol.quickConnectPreflight(target, listOf(target), topology),
        )
    }

    @Test fun `preflight refuses a soundbar that is a grouped room coordinator`() {
        val target = SonosDevice("arc", "Sample Room", "Sonos Arc", "10.23.45.8")
        val topology = """
            <ZoneGroups><ZoneGroup Coordinator="arc">
              <ZoneGroupMember UUID="arc" /><ZoneGroupMember UUID="other" />
            </ZoneGroup></ZoneGroups>
        """.trimIndent()
        assertEquals(
            QuickConnectPreflight.Refused("Un-group the selected device before switching TV audio"),
            SonosProtocol.quickConnectPreflight(target, listOf(target, SonosDevice("other", "Secondary Room", "Era 100", "10.23.45.9")), topology),
        )
    }


    @Test fun `readback must match the exact selected soundbar TV URI`() {
        assertEquals(
            QuickConnectVerification.Failed("TV input selection could not be verified for this device"),
            SonosProtocol.verifyQuickConnect("arc", "x-sonos-htastream:other:spdif", setOf("arc"), setOf("arc")),
        )
        assertEquals(
            QuickConnectVerification.Failed("Configured theatre members changed"),
            SonosProtocol.verifyQuickConnect("arc", "x-sonos-htastream:arc:spdif", setOf("arc", "left"), setOf("arc")),
        )
    }
}
