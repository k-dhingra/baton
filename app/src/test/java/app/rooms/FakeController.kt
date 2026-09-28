package app.rooms

import app.rooms.sonos.*

/** Synthetic devices and media for offline UI tests; not a captured household. */
class FakeController(val spares: Boolean = true, val longNames: Boolean = false) : SonosController {
    override val lastDiscoveryMessage = "5 Sonos rooms found"
    override var lastCommandError = ""

    val arc = SonosDevice("ARC", if (longNames) "Very Long Example Room Name For Layout" else "Room A", "Sonos Arc", "192.0.2.2")
    val beam = SonosDevice("BEAM", "Room B", "Sonos Beam", "192.0.2.3")
    val eraL = SonosDevice("ERA_L", "Room C", "Sonos Era 100", "192.0.2.5")
    val eraR = SonosDevice("ERA_R", "Room D", "Sonos Era 100", "192.0.2.6")
    val sub = SonosDevice("SUB2", "Sub", "Sonos Sub Mini", "192.0.2.7")
    private val theatreSats = listOf(
        SonosDevice("SAT_L", "Room A", "Sonos Era 100", "192.0.2.8"),
        SonosDevice("SAT_R", "Room A", "Sonos Era 100", "192.0.2.9"),
        SonosDevice("SUB1", "Room A", "Sonos Sub", "192.0.2.10"),
    )
    val rooms = if (spares) listOf(arc, beam, eraL, eraR, sub) else listOf(arc, beam)

    val members: List<SonosZoneMember> = buildList {
        add(SonosZoneMember("ARC", arc.roomName, "ARC", 1, false, false, "ARC:LF,RF;SAT_L:LR;SAT_R:RR;SUB1:SW", ""))
        theatreSats.forEach { add(SonosZoneMember(it.uid, arc.roomName, "ARC", 1, true, true, "", "")) }
        // Rooms B and C play together.
        add(SonosZoneMember("BEAM", "Room B", "BEAM", if (spares) 2 else 1, false, false, "", ""))
        if (spares) {
            add(SonosZoneMember("ERA_L", "Room C", "BEAM", 2, false, false, "", ""))
            add(SonosZoneMember("ERA_R", "Room D", "ERA_R", 1, false, false, "", ""))
            add(SonosZoneMember("SUB2", "Sub", "SUB2", 1, false, false, "", ""))
        }
    }

    override fun discoverSystem() = SonosSystem(rooms, (rooms + theatreSats).map { SonosProduct(it, it in rooms) })
    override fun getRoomState(device: SonosDevice) = when (device.uid) {
        "BEAM", "ERA_L" -> SonosRoomState("x-rincon-queue:BEAM#0", "PLAYING", 32, false,
            if (longNames) "A Really Quite Extraordinarily Long Sample Track Title" else "Sample Track",
            "Example Artist", "Sample Album", "", "", 94, 303)
        "ARC" -> SonosRoomState("x-sonos-htastream:ARC:spdif", "PLAYING", 48, false)
        else -> SonosRoomState("", "STOPPED", 20, false)
    }
    override fun zoneMembers(any: SonosDevice) = members
    override fun groupInfo(device: SonosDevice) = members.firstOrNull { it.uid == device.uid }?.let { m ->
        m.groupCoordinator to members.filter { it.groupCoordinator == m.groupCoordinator && !it.invisible }.map { it.uid }
    }
    override fun queue(device: SonosDevice) = listOf(
        SonosDidlItem("Q:0/1", "Sample Track One", "x-file-cifs://example.invalid/a", "Example Artist", "Sample Album"),
        SonosDidlItem("Q:0/2", "Sample Track Two", "x-file-cifs://example.invalid/b", "Example Artist", "Sample Album"),
        SonosDidlItem("Q:0/3", "Sample Track Three", "x-file-cifs://example.invalid/c", "Example Artist", "Sample Album"),
    )
    override fun favourites(device: SonosDevice) = listOf(
        SonosDidlItem("FV:2/1", "Discover Sonos Radio", null, description = "From Sonos Radio"),
        SonosDidlItem("FV:2/2", "Sample Radio", "x-sonosapi-stream:sample", description = "Radio station"),
        SonosDidlItem("FV:2/3", "Sample Album", "x-rincon-cpcontainer:album", description = "Album · Example Artist"),
    )
    override fun artwork(url: String): ByteArray? = null
    override fun bass(device: SonosDevice) = 2
    override fun treble(device: SonosDevice) = -1
    override fun loudness(device: SonosDevice) = true
    override fun soundbarMode(device: SonosDevice, type: String) = if (device.uid == "ARC") type == "DialogLevel" else null
    override fun eqValue(device: SonosDevice, type: String) = if (device.uid != "ARC") null else when (type) {
        "SurroundEnable", "SubEnable", "SurroundMode" -> 1; "SubGain" -> -2; "SurroundLevel" -> 3; else -> 0
    }
    override fun deviceSettings(device: SonosDevice) = SonosDeviceSettings(true, true)
    override fun currentTransportActions(device: SonosDevice) = setOf("Play", "Pause", "Next", "Previous", "Seek")
    override fun groupVolume(device: SonosDevice) = 30
    override fun volume(device: SonosDevice) = if (device.uid == "BEAM") 32 else 28

    override fun applyGroup(rooms: List<SonosDevice>, coordinatorUid: String, desiredMembers: Set<String>) = SonosGroupResult(true, "ok")
    override fun identify(device: SonosDevice) = true
    override fun leaveGroup(device: SonosDevice) = true
    override fun next(device: SonosDevice) = true
    override fun nudgeVolume(device: SonosDevice, delta: Int, grouped: Boolean) = 34
    override fun pause(device: SonosDevice) = true
    override fun play(device: SonosDevice) = true
    override fun playFavourite(device: SonosDevice, item: SonosDidlItem) = true
    override fun playQueueItem(device: SonosDevice, index: Int) = true
    override fun previous(device: SonosDevice) = true
    override fun quickConnect(storedTarget: SonosDevice): QuickConnectOutcome = QuickConnectOutcome.Failed("fake")
    override fun renameRoom(device: SonosDevice, name: String) = true
    override fun runSetup(task: SetupTask, devices: Map<String, SonosDevice>, progress: (String) -> Unit) = SetupOutcome.Done("Done")
    override fun seek(device: SonosDevice, seconds: Int) = true
    override fun setBass(device: SonosDevice, value: Int) = true
    override fun setEqValue(device: SonosDevice, type: String, value: Int) = true
    override fun setGroupVolume(device: SonosDevice, value: Int) = true
    override fun setLoudness(device: SonosDevice, value: Boolean) = true
    override fun setMute(device: SonosDevice, value: Boolean) = true
    override fun setSoundbarMode(device: SonosDevice, type: String, enabled: Boolean) = true
    override fun setStatusLight(device: SonosDevice, enabled: Boolean) = true
    override fun setTouchControls(device: SonosDevice, enabled: Boolean) = true
    override fun setTreble(device: SonosDevice, value: Int) = true
    override fun setVolume(device: SonosDevice, value: Int) = true
}
