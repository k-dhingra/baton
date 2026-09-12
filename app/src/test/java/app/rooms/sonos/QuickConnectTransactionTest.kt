package app.rooms.sonos

// Synthetic LAN fixtures only; no observed household topology is used.

import org.junit.Assert.*
import org.junit.Test

class QuickConnectTransactionTest {
    private val stored = SonosDevice("arc", "Sample Room", "Sonos Arc", "10.23.45.8")
    private val fresh = stored.copy(ip = "10.23.45.18")
    private val topology = """<ZoneGroups><ZoneGroup Coordinator="arc"><ZoneGroupMember UUID="arc" HTSatChanMapSet="arc:LF,RF;left:LR;sub:SW" /></ZoneGroup></ZoneGroups>"""
    private val calls = mutableListOf<String>()
    private fun run(
        resolved: SonosDevice? = fresh,
        before: String? = topology,
        after: String? = topology,
        uri: String? = SonosProtocol.tvUri("arc"),
        writeOk: Boolean = true,
    ): QuickConnectOutcome {
        var reads = 0
        return QuickConnectTransaction.run(stored,
            resolve = { calls += "resolve"; resolved },
            topology = { target -> assertEquals(fresh, target); calls += "topology"; if (reads++ == 0) before else after },
            selectTv = { target -> assertEquals(fresh, target); calls += "select"; writeOk },
            readUri = { target -> assertEquals(fresh, target); calls += "uri"; uri },
        )
    }
    @Test fun `success uses freshly resolved target and verifies actual IO sequence`() {
        assertTrue(run() is QuickConnectOutcome.Selected)
        assertEquals(listOf("resolve", "topology", "select", "uri", "topology"), calls)
    }
    @Test fun `offline target never mutates another room`() {
        assertTrue(run(resolved = null) is QuickConnectOutcome.Failed)
        assertEquals(listOf("resolve"), calls)
    }
    @Test fun `wrong resolved identity cannot receive commands`() {
        assertTrue(run(resolved = fresh.copy(uid = "other")) is QuickConnectOutcome.Failed)
        assertEquals(listOf("resolve"), calls)
    }
    @Test fun `missing topology fails before source mutation`() {
        assertTrue(run(before = null) is QuickConnectOutcome.Failed)
        assertEquals(listOf("resolve", "topology"), calls)
    }
    @Test fun `grouped target fails before source mutation`() {
        val grouped = topology.replace("</ZoneGroup>", "<ZoneGroupMember UUID=\"other\" /></ZoneGroup>")
        assertTrue(run(before = grouped) is QuickConnectOutcome.Failed)
        assertEquals(listOf("resolve", "topology"), calls)
    }
    @Test fun `rejected write stops the transaction`() {
        assertTrue(run(writeOk = false) is QuickConnectOutcome.Failed)
        assertEquals(listOf("resolve", "topology", "select"), calls)
    }
    @Test fun `wrong input readback stops before topology success`() {
        assertTrue(run(uri = SonosProtocol.tvUri("other")) is QuickConnectOutcome.Failed)
        assertEquals(listOf("resolve", "topology", "select", "uri"), calls)
    }
    @Test fun `lost member or postwrite topology cannot be reported as success`() {
        assertTrue(run(after = topology.replace(";left:LR", "")) is QuickConnectOutcome.Failed)
        assertTrue(run(after = null) is QuickConnectOutcome.Failed)
    }
    @Test fun `network exception cannot become success`() {
        try {
            QuickConnectTransaction.run(stored, { throw java.io.IOException("offline") }, { topology }, { true }, { SonosProtocol.tvUri("arc") })
            fail("An IO failure must not report success")
        } catch (_: java.io.IOException) { }
    }
}
