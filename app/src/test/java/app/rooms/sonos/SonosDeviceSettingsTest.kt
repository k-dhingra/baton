package app.rooms.sonos

// Synthetic LAN fixtures only; no observed household topology is used.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosDeviceSettingsTest {
    @Test fun `device properties use exact Sonos actions and arguments`() {
        assertEquals("/DeviceProperties/Control", SonosProtocol.servicePath("DeviceProperties"))
        assertEquals("<DesiredLEDState>On</DesiredLEDState>", SonosProtocol.devicePropertyArguments("led", true))
        assertEquals("<DesiredButtonLockState>Off</DesiredButtonLockState>", SonosProtocol.devicePropertyArguments("touch", true))
        assertEquals("<DesiredButtonLockState>On</DesiredButtonLockState>", SonosProtocol.devicePropertyArguments("touch", false))
    }

    @Test fun `room names are validated and xml escaped`() {
        assertEquals(
            "<DesiredZoneName>TV &amp; Music</DesiredZoneName><DesiredIcon></DesiredIcon><DesiredConfiguration></DesiredConfiguration>",
            SonosProtocol.roomNameArguments(" TV & Music "),
        )
        assertEquals(null, SonosProtocol.roomNameArguments("   "))
    }

    @Test fun `home theatre eq controls use exact ranges`() {
        assertEquals("<InstanceID>0</InstanceID><EQType>SurroundLevel</EQType><DesiredValue>-15</DesiredValue>", SonosProtocol.eqArguments("SurroundLevel", -15))
        assertEquals("<InstanceID>0</InstanceID><EQType>MusicSurroundLevel</EQType><DesiredValue>15</DesiredValue>", SonosProtocol.eqArguments("MusicSurroundLevel", 15))
        assertEquals("<InstanceID>0</InstanceID><EQType>AudioDelay</EQType><DesiredValue>5</DesiredValue>", SonosProtocol.eqArguments("AudioDelay", 5))
        assertEquals(null, SonosProtocol.eqArguments("SurroundLevel", 16))
        assertEquals(null, SonosProtocol.eqArguments("AudioDelay", 6))
    }

    @Test fun `on off state parsing is explicit`() {
        assertTrue(SonosProtocol.parseOnOff("<CurrentLEDState>On</CurrentLEDState>", "CurrentLEDState")!!)
        assertFalse(SonosProtocol.parseOnOff("<CurrentButtonLockState>Off</CurrentButtonLockState>", "CurrentButtonLockState")!!)
    }
}
