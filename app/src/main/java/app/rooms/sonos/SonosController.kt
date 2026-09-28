package app.rooms.sonos

/** Everything the UI needs from the speakers. SonosClient talks to real hardware; tests use a fake. */
interface SonosController {
    val lastDiscoveryMessage: String
    val lastCommandError: String
    fun applyGroup(rooms: List<SonosDevice>, coordinatorUid: String, desiredMembers: Set<String>): SonosGroupResult
    fun artwork(url: String): ByteArray?
    fun bass(device: SonosDevice): Int?
    fun currentTransportActions(device: SonosDevice): Set<String>
    fun deviceSettings(device: SonosDevice): SonosDeviceSettings
    fun discoverSystem(): SonosSystem
    fun eqValue(device: SonosDevice, type: String): Int?
    fun favourites(device: SonosDevice): List<SonosDidlItem>
    fun getRoomState(device: SonosDevice): SonosRoomState
    fun groupInfo(device: SonosDevice): Pair<String, List<String>>?
    fun groupVolume(device: SonosDevice): Int?
    fun identify(device: SonosDevice): Boolean
    fun leaveGroup(device: SonosDevice): Boolean
    fun loudness(device: SonosDevice): Boolean?
    fun next(device: SonosDevice): Boolean
    fun nudgeVolume(device: SonosDevice, delta: Int, grouped: Boolean): Int?
    fun pause(device: SonosDevice): Boolean
    fun play(device: SonosDevice): Boolean
    fun playFavourite(device: SonosDevice, item: SonosDidlItem): Boolean
    fun playQueueItem(device: SonosDevice, index: Int): Boolean
    fun previous(device: SonosDevice): Boolean
    fun queue(device: SonosDevice): List<SonosDidlItem>
    fun quickConnect(storedTarget: SonosDevice): QuickConnectOutcome
    fun renameRoom(device: SonosDevice, name: String): Boolean
    fun runSetup(task: SetupTask, devices: Map<String, SonosDevice>, progress: (String) -> Unit): SetupOutcome
    fun seek(device: SonosDevice, seconds: Int): Boolean
    fun setBass(device: SonosDevice, value: Int): Boolean
    fun setEqValue(device: SonosDevice, type: String, value: Int): Boolean
    fun setGroupVolume(device: SonosDevice, value: Int): Boolean
    fun setLoudness(device: SonosDevice, value: Boolean): Boolean
    fun setMute(device: SonosDevice, value: Boolean): Boolean
    fun setSoundbarMode(device: SonosDevice, type: String, enabled: Boolean): Boolean
    fun setStatusLight(device: SonosDevice, enabled: Boolean): Boolean
    fun setTouchControls(device: SonosDevice, enabled: Boolean): Boolean
    fun setTreble(device: SonosDevice, value: Int): Boolean
    fun setVolume(device: SonosDevice, value: Int): Boolean
    fun soundbarMode(device: SonosDevice, type: String): Boolean?
    fun treble(device: SonosDevice): Int?
    fun volume(device: SonosDevice): Int?
    fun zoneMembers(any: SonosDevice): List<SonosZoneMember>?
}
