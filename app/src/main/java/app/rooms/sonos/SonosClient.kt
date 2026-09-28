package app.rooms.sonos

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Direct, local-only Sonos UPnP client. Calls are synchronous; invoke off the UI thread. */
data class SonosRoomState(
    val source: String?, val transport: String?, val volume: Int?,
    val mute: Boolean? = null, val title: String = "", val artist: String = "", val album: String = "",
    val artworkUri: String = "", val trackUri: String = "", val positionSeconds: Int? = null, val durationSeconds: Int? = null,
    val bass: Int? = null, val treble: Int? = null, val loudness: Boolean? = null
)

data class SonosGroupResult(val success: Boolean, val message: String)
data class SonosDeviceSettings(val statusLight: Boolean?, val touchControls: Boolean?)
sealed class QuickConnectOutcome {
    data class Selected(val theatre: SonosTheatre) : QuickConnectOutcome()
    data class Failed(val message: String) : QuickConnectOutcome()
}

class SonosClient(
    context: Context,
    private val timeoutMs: Int = 1500,
) : SonosController {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    override var lastDiscoveryMessage: String = "Discovery not started"
        private set

    @Volatile
    override var lastCommandError: String = ""
        private set

    override fun discoverSystem(): SonosSystem {
        val network = wifiNetwork() ?: run {
            lastDiscoveryMessage = "No usable Wi-Fi route found"
            return SonosSystem(emptyList(), emptyList())
        }
        val locations = linkedSetOf<String>()
        val multicastFailure = runCatching {
            val lock = wifi.createMulticastLock("rooms-sonos-discovery").apply { setReferenceCounted(false) }
            try {
                lock.acquire()
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    network.bindSocket(socket)
                    val buffer = ByteArray(8192)
                    listOf("urn:schemas-upnp-org:device:ZonePlayer:1", "ssdp:all").forEach { searchTarget ->
                        val query = SonosProtocol.ssdpSearchQuery(searchTarget)
                        val bytes = query.toByteArray(StandardCharsets.US_ASCII)
                        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(SSDP_HOST), SSDP_PORT))
                        while (true) {
                            val packet = DatagramPacket(buffer, buffer.size)
                            try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { break }
                            SonosProtocol.locationFromSsdp(String(packet.data, packet.offset, packet.length, StandardCharsets.US_ASCII))?.takeIf(SonosProtocol::isLocalSonosLocation)?.let(locations::add)
                        }
                    }
                }
            } finally { if (lock.isHeld) lock.release() }
        }.exceptionOrNull()?.javaClass?.simpleName
        fun descriptions() = locations.mapNotNull { location ->
            val ip = runCatching { URI(location).host }.getOrNull() ?: return@mapNotNull null
            readDescription(location)?.let { SonosProtocol.deviceFromDescription(it, ip) }
        }.distinctBy(SonosDevice::uid)
        var devices = descriptions()
        var scanFailure: String? = null
        if (devices.isEmpty()) {
            runCatching { scanLocalSubnet(network) }.onSuccess { locations += it }.onFailure { scanFailure = it.javaClass.simpleName }
            devices = descriptions()
        }
        val zoneState = devices.firstNotNullOfOrNull(::zoneState)
        if (zoneState != null) {
            val topologyProducts = SonosProtocol.productLocations(zoneState).mapNotNull { location ->
                val ip = runCatching { URI(location).host }.getOrNull() ?: return@mapNotNull null
                readDescription(location)?.let { SonosProtocol.deviceFromDescription(it, ip) }
            }
            devices = (devices + topologyProducts).distinctBy(SonosDevice::uid)
        }
        val system = SonosSystem(devices.filter { it.uid in SonosProtocol.coordinatorUidsForInventory(devices, zoneState) }, SonosProtocol.inventoryProducts(devices, zoneState))
        lastDiscoveryMessage = SonosProtocol.discoveryMessage(locations.size, system.rooms.size, multicastFailure, scanFailure)
        return system
    }

    fun discover(): List<SonosDevice> = discoverSystem().rooms


    private fun wifiNetwork(): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            connectivity.getLinkProperties(network)?.linkAddresses?.any {
                it.address is Inet4Address && it.address.isSiteLocalAddress
            } == true
    }

    private fun scanLocalSubnet(network: Network): Set<String> {
        val linkAddress = connectivity.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && it.address.isSiteLocalAddress }
            ?: return emptySet()
        val phoneIp = linkAddress.address.hostAddress ?: return emptySet()
        val hosts = SonosProtocol.localScanHosts(phoneIp, linkAddress.prefixLength)
        if (hosts.isEmpty() && linkAddress.prefixLength !in 22..30) {
            throw UnsupportedOperationException("Fallback scan does not support /${linkAddress.prefixLength}")
        }
        val pool = Executors.newFixedThreadPool(32)
        return try {
            pool.invokeAll(hosts.map { host ->
                Callable {
                    runCatching {
                        Socket().use { socket ->
                            network.bindSocket(socket)
                            socket.connect(InetSocketAddress(host, 1400), 250)
                        }
                        "http://$host:1400/xml/device_description.xml"
                    }.getOrNull()
                }
            }).mapNotNull { it.get() }.toSet()
        } finally {
            pool.shutdownNow()
        }
    }

    fun readDescription(device: SonosDevice): String? = readDescription("http://${device.ip}:1400/xml/device_description.xml")

    private fun readDescription(location: String): String? = request("GET", location, null, null)

    fun zoneState(device: SonosDevice): String? = soap(device, "ZoneGroupTopology", "GetZoneGroupState", "")
        ?.let(SonosProtocol::zoneStateFromSoap)

    fun transportState(device: SonosDevice): String? = soap(
        device, "AVTransport", "GetTransportInfo",
        "<InstanceID>0</InstanceID>",
    )

    override fun getRoomState(device: SonosDevice): SonosRoomState {
        val target = transportTarget(device)
        val position = soap(target, "AVTransport", "GetPositionInfo", "<InstanceID>0</InstanceID>").orEmpty()
        val media = soap(target, "AVTransport", "GetMediaInfo", "<InstanceID>0</InstanceID>").orEmpty()
        val now = SonosProtocol.nowPlayingFromPosition(position, target.ip)
        return SonosRoomState(
            source = SonosProtocol.soapText(media, "CurrentURI"),
            transport = transportState(target)?.let { SonosProtocol.soapText(it, "CurrentTransportState") },
            volume = volume(device), mute = mute(device), title = now?.title.orEmpty(), artist = now?.artist.orEmpty(), album = now?.album.orEmpty(),
            artworkUri = now?.artworkUri.orEmpty(), trackUri = now?.trackUri.orEmpty(), positionSeconds = now?.positionSeconds, durationSeconds = now?.durationSeconds,
            bass = null, treble = null, loudness = null
        )
    }

    fun mute(device: SonosDevice): Boolean? = SonosProtocol.parseBooleanValue(soap(device, "RenderingControl", "GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>").orEmpty(), "CurrentMute")
    override fun setMute(device: SonosDevice, value: Boolean): Boolean = soap(device, "RenderingControl", "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>${if (value) 1 else 0}</DesiredMute>") != null
    override fun setBass(device: SonosDevice, value: Int): Boolean = value in -10..10 && soap(device, "RenderingControl", "SetBass", SonosProtocol.soundArguments("bass", value)) != null && bass(device) == value
    override fun setTreble(device: SonosDevice, value: Int): Boolean = value in -10..10 && soap(device, "RenderingControl", "SetTreble", SonosProtocol.soundArguments("treble", value)) != null && treble(device) == value
    override fun setLoudness(device: SonosDevice, value: Boolean): Boolean = soap(device, "RenderingControl", "SetLoudness", SonosProtocol.soundArguments("loudness", if (value) 1 else 0)) != null && loudness(device) == value
    override fun bass(device: SonosDevice): Int? = eq(device, "Bass")
    override fun treble(device: SonosDevice): Int? = eq(device, "Treble")
    override fun loudness(device: SonosDevice): Boolean? = SonosProtocol.parseBooleanValue(
        soap(device, "RenderingControl", "GetLoudness", "<InstanceID>0</InstanceID><Channel>Master</Channel>").orEmpty(),
        "CurrentLoudness",
    )
    override fun previous(device: SonosDevice): Boolean = soap(transportTarget(device), "AVTransport", "Previous", "<InstanceID>0</InstanceID>") != null
    override fun next(device: SonosDevice): Boolean = soap(transportTarget(device), "AVTransport", "Next", "<InstanceID>0</InstanceID>") != null
    override fun currentTransportActions(device: SonosDevice): Set<String> = soap(transportTarget(device), "AVTransport", "GetCurrentTransportActions", "<InstanceID>0</InstanceID>")?.let(SonosProtocol::transportActions).orEmpty()
    override fun seek(device: SonosDevice, seconds: Int): Boolean {
        if (seconds < 0) return false
        val target = "%02d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
        return soap(transportTarget(device), "AVTransport", "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>$target</Target>") != null
    }
    override fun queue(device: SonosDevice): List<SonosDidlItem> = browse(transportTarget(device), "Q:0")
    override fun favourites(device: SonosDevice): List<SonosDidlItem> = browse(transportTarget(device), "FV:2")
    override fun playQueueItem(device: SonosDevice, index: Int): Boolean {
        if (index < 0) return false
        val target = transportTarget(device)
        val uri = "x-rincon-queue:${target.uid}#0"
        return soap(target, "AVTransport", "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${escape(uri)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>") != null &&
            soap(target, "AVTransport", "Seek", "<InstanceID>0</InstanceID><Unit>TRACK_NR</Unit><Target>${index + 1}</Target>") != null && play(target)
    }
    fun joinGroup(device: SonosDevice, coordinatorUid: String): Boolean = soap(device, "AVTransport", "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${escape(SonosProtocol.groupJoinUri(coordinatorUid))}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>") != null
    override fun leaveGroup(device: SonosDevice): Boolean = soap(device, "AVTransport", SonosProtocol.groupLeaveAction, "<InstanceID>0</InstanceID>") != null
    fun groupMembers(device: SonosDevice): Set<String> = zoneState(device)?.let { SonosProtocol.groupMembers(it, device.uid) }.orEmpty()
    override fun applyGroup(rooms: List<SonosDevice>, coordinatorUid: String, desiredMembers: Set<String>): SonosGroupResult {
        val coordinator = rooms.firstOrNull { it.uid == coordinatorUid }
            ?: return SonosGroupResult(false, "Coordinator is unavailable")
        val roomUids = rooms.map(SonosDevice::uid).toSet()
        val desired = (desiredMembers intersect roomUids) + coordinatorUid
        val before = zoneState(coordinator)
            ?: return SonosGroupResult(false, "Could not read the current group")
        val current = SonosProtocol.groupMembers(before, coordinatorUid)
        val failed = mutableListOf<String>()
        rooms.filter { it.uid != coordinatorUid }.forEach { room ->
            val changed = when {
                room.uid in desired && room.uid !in current -> joinGroup(room, coordinatorUid)
                room.uid !in desired && room.uid in current -> leaveGroup(room)
                else -> true
            }
            if (!changed) failed += room.roomName.ifBlank { room.modelName }
        }
        val after = zoneState(coordinator)
            ?: return SonosGroupResult(false, "Changes sent, but topology readback failed")
        val actual = SonosProtocol.groupMembers(after, coordinatorUid) intersect roomUids
        return when {
            failed.isNotEmpty() -> SonosGroupResult(false, "Some rooms failed: ${failed.joinToString()}")
            actual != desired -> SonosGroupResult(false, "Sonos reported a partial group; refresh and retry")
            else -> SonosGroupResult(true, "Group applied and verified")
        }
    }
    override fun soundbarMode(device: SonosDevice, type: String): Boolean? {
        if (!supportsSoundbarMode(device.modelName)) return null
        val response = soap(device, "RenderingControl", "GetEQ", "<InstanceID>0</InstanceID><EQType>${escape(type)}</EQType>").orEmpty()
        return SonosProtocol.parseBooleanValue(response, "CurrentValue")
    }
    override fun setSoundbarMode(device: SonosDevice, type: String, enabled: Boolean): Boolean =
        supportsSoundbarMode(device.modelName) && soap(
            device,
            "RenderingControl",
            "SetEQ",
            "<InstanceID>0</InstanceID><EQType>${escape(type)}</EQType><DesiredValue>${if (enabled) 1 else 0}</DesiredValue>",
        ) != null && soundbarMode(device, type) == enabled
    override fun deviceSettings(device: SonosDevice) = SonosDeviceSettings(statusLight(device), touchControls(device))
    fun statusLight(device: SonosDevice): Boolean? = SonosProtocol.parseOnOff(
        soap(device, "DeviceProperties", "GetLEDState", "").orEmpty(), "CurrentLEDState",
    )
    override fun setStatusLight(device: SonosDevice, enabled: Boolean): Boolean =
        soap(device, "DeviceProperties", "SetLEDState", SonosProtocol.devicePropertyArguments("led", enabled)) != null && statusLight(device) == enabled
    fun touchControls(device: SonosDevice): Boolean? = SonosProtocol.parseOnOff(
        soap(device, "DeviceProperties", "GetButtonLockState", "").orEmpty(), "CurrentButtonLockState",
    )?.not()
    override fun setTouchControls(device: SonosDevice, enabled: Boolean): Boolean =
        soap(device, "DeviceProperties", "SetButtonLockState", SonosProtocol.devicePropertyArguments("touch", enabled)) != null && touchControls(device) == enabled
    override fun renameRoom(device: SonosDevice, name: String): Boolean {
        val arguments = SonosProtocol.roomNameArguments(name) ?: return false
        if (soap(device, "DeviceProperties", "SetZoneAttributes", arguments) == null) return false
        return soap(device, "DeviceProperties", "GetZoneAttributes", "")
            ?.let { SonosProtocol.soapText(it, "CurrentZoneName") == name.trim() } == true
    }
    override fun eqValue(device: SonosDevice, type: String): Int? = soap(
        device, "RenderingControl", "GetEQ", "<InstanceID>0</InstanceID><EQType>${escape(type)}</EQType>",
    )?.let { SonosProtocol.parseIntValue(it, "CurrentValue") }
    override fun setEqValue(device: SonosDevice, type: String, value: Int): Boolean {
        val arguments = SonosProtocol.eqArguments(type, value) ?: return false
        val allowed = supportsSoundbarMode(device.modelName) || type.startsWith("Sub")
        return allowed && soap(device, "RenderingControl", "SetEQ", arguments) != null && eqValue(device, type) == value
    }
    private fun supportsSoundbarMode(model: String) = listOf("arc", "beam", "ray", "playbar").any { model.contains(it, true) }
    private fun browse(device: SonosDevice, id: String): List<SonosDidlItem> = SonosProtocol.parseDidlItems(
        soap(device, "ContentDirectory", "Browse", "<ObjectID>$id</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>50</RequestedCount><SortCriteria></SortCriteria>")?.let { SonosProtocol.soapText(it, "Result") }.orEmpty()
    )
    private fun eq(device: SonosDevice, type: String): Int? {
        val action = SonosProtocol.soundAction(type, false)
        val tag = if (type.equals("bass", true)) "CurrentBass" else "CurrentTreble"
        return SonosProtocol.parseIntValue(soap(device, "RenderingControl", action, "<InstanceID>0</InstanceID><Channel>Master</Channel>").orEmpty(), tag)
    }


    // ---- Group volume -------------------------------------------------------------------

    /** Coordinator uid and visible member uids for the group containing [device]. */
    override fun groupInfo(device: SonosDevice): Pair<String, List<String>>? = zoneState(device)?.let { SonosProtocol.groupContaining(it, device.uid) }

    override fun groupVolume(device: SonosDevice): Int? {
        val target = transportTarget(device)
        soap(target, "GroupRenderingControl", "SnapshotGroupVolume", "<InstanceID>0</InstanceID>")
        return soap(target, "GroupRenderingControl", "GetGroupVolume", "<InstanceID>0</InstanceID>")
            ?.let { SonosProtocol.parseIntValue(it, "CurrentVolume") }
    }

    override fun setGroupVolume(device: SonosDevice, value: Int): Boolean {
        if (value !in 0..100) return false
        val target = transportTarget(device)
        soap(target, "GroupRenderingControl", "SnapshotGroupVolume", "<InstanceID>0</InstanceID>")
        return soap(target, "GroupRenderingControl", "SetGroupVolume", "<InstanceID>0</InstanceID><DesiredVolume>$value</DesiredVolume>") != null
    }

    /** Hardware volume keys: nudges the whole group when grouped, otherwise the room. */
    override fun nudgeVolume(device: SonosDevice, delta: Int, grouped: Boolean): Int? = if (grouped) {
        val target = transportTarget(device)
        soap(target, "GroupRenderingControl", "SnapshotGroupVolume", "<InstanceID>0</InstanceID>")
        soap(target, "GroupRenderingControl", "SetRelativeGroupVolume", "<InstanceID>0</InstanceID><Adjustment>$delta</Adjustment>")
            ?.let { SonosProtocol.parseIntValue(it, "NewVolume") }
    } else {
        soap(device, "RenderingControl", "SetRelativeVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><Adjustment>$delta</Adjustment>")
            ?.let { SonosProtocol.parseIntValue(it, "NewVolume") }
    }

    // ---- Favourites & artwork -----------------------------------------------------------

    override fun playFavourite(device: SonosDevice, item: SonosDidlItem): Boolean {
        val target = transportTarget(device)
        return when (val plan = SonosProtocol.favouritePlayback(item)) {
            is FavouritePlayback.Unsupported -> { lastCommandError = plan.reason; false }
            is FavouritePlayback.Direct -> soap(target, "AVTransport", "SetAVTransportURI",
                "<InstanceID>0</InstanceID><CurrentURI>${escape(plan.uri)}</CurrentURI><CurrentURIMetaData>${escape(plan.metadata)}</CurrentURIMetaData>") != null && play(target)
            is FavouritePlayback.Queue -> {
                soap(target, "AVTransport", "RemoveAllTracksFromQueue", "<InstanceID>0</InstanceID>") != null &&
                    soap(target, "AVTransport", "AddURIToQueue",
                        "<InstanceID>0</InstanceID><EnqueuedURI>${escape(plan.uri)}</EnqueuedURI><EnqueuedURIMetaData>${escape(plan.metadata)}</EnqueuedURIMetaData><DesiredFirstTrackNumberEnqueued>0</DesiredFirstTrackNumberEnqueued><EnqueueAsNext>0</EnqueueAsNext>") != null &&
                    playQueueItem(target, 0)
            }
        }
    }

    /** Album art is fetched only from the speaker that reported it (validated local :1400 URL). */
    override fun artwork(url: String): ByteArray? {
        if (!SonosProtocol.isSafeArtworkUri(url)) return null
        val network = wifiNetwork() ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (network.openConnection(URL(url)) as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = 4000; instanceFollowRedirects = false
            }
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.use { stream ->
                val bytes = stream.readBytes()
                bytes.takeIf { it.size <= 4 * 1024 * 1024 }
            }
        } catch (_: Exception) { null } finally { connection?.disconnect() }
    }

    // ---- Speaker setup ------------------------------------------------------------------

    /** Blinks the status light so you can tell which physical speaker is which, then restores it. */
    override fun identify(device: SonosDevice): Boolean {
        val original = statusLight(device) ?: return false
        var ok = true
        repeat(4) {
            ok = ok && soap(device, "DeviceProperties", "SetLEDState", SonosProtocol.devicePropertyArguments("led", !original)) != null
            Thread.sleep(450)
            ok = ok && soap(device, "DeviceProperties", "SetLEDState", SonosProtocol.devicePropertyArguments("led", original)) != null
            Thread.sleep(450)
        }
        return ok
    }

    override fun zoneMembers(any: SonosDevice): List<SonosZoneMember>? = zoneState(any)?.let(SonosProtocol::zoneMembers)

    /**
     * Ordered setup workflow:
     * fresh topology → re-validate → ungroup helpers → one DeviceProperties command → poll until verified.
     * Ungrouping can persist even if a later step fails; report that partial change honestly.
     * Never retries a bond, never resets, never touches rooms outside the task.
     */
    @Synchronized
    override fun runSetup(task: SetupTask, devices: Map<String, SonosDevice>, progress: (String) -> Unit): SetupOutcome {
        val command = SonosSetup.command(task)
        val target = devices[command.targetUid]?.let(::resolve)
            ?: return SetupOutcome.Failed("Couldn't reach ${devices[command.targetUid]?.roomName ?: "that speaker"}. Check it's powered on and on this Wi-Fi.")
        progress("Checking your current setup…")
        val before = zoneMembers(target) ?: return SetupOutcome.Failed("Couldn't read your speaker setup. Check Wi-Fi and try again.")
        SonosSetup.preflight(task, before)?.let { return SetupOutcome.Failed(it) }
        var ungrouped = false
        fun partialChangeWarning() = if (ungrouped)
            " Some rooms were removed from their groups. Refresh Rooms and re-group them if needed."
        else " Check Rooms before retrying."
        SonosSetup.mustBeStandalone(task).forEach { uid ->
            val member = before.firstOrNull { it.uid == uid }
            if (member != null && member.groupSize > 1) {
                val device = devices[uid] ?: return SetupOutcome.Failed("A selected speaker isn't reachable.${partialChangeWarning()}")
                progress("Taking ${member.zoneName} out of its group…")
                if (!leaveGroup(device)) return SetupOutcome.Failed("Couldn't take ${member.zoneName} out of its group. ${lastCommandError}${partialChangeWarning()}".trim())
                ungrouped = true
            }
        }
        progress("Sending the change to ${target.roomName.ifBlank { target.modelName }}…")
        if (soap(target, "DeviceProperties", command.action, command.arguments) == null) {
            val reason = lastCommandError.ifBlank { "the speaker didn't confirm it" }
            return SetupOutcome.Failed("Couldn't confirm the change ($reason).${partialChangeWarning()}")
        }
        progress("Waiting for the speakers to confirm…")
        repeat(15) {
            Thread.sleep(2_000)
            val after = zoneMembers(target)
            if (after != null && SonosSetup.verified(task, after)) {
                if (task is SetupTask.StereoPair && task.roomName.isNotBlank() && task.roomName != task.left.roomName) {
                    progress("Naming the room…")
                    if (!renameRoom(target, task.roomName)) return SetupOutcome.Done("Paired. The name couldn't be changed; rename it in Room settings.")
                }
                return SetupOutcome.Done(doneMessage(task))
            }
        }
        return SetupOutcome.Pending("The change was sent, but the speakers haven't confirmed yet. Wait a minute, then refresh. If it still hasn't changed, power-cycle the speakers involved.${if (ungrouped) partialChangeWarning() else ""}")
    }

    private fun doneMessage(task: SetupTask) = when (task) {
        is SetupTask.AddSub -> "${task.sub.roomName.ifBlank { "Sub" }} is now part of ${task.main.roomName}. Adjust Sub level in Sound."
        is SetupTask.AddSurrounds -> "Surrounds added to ${task.main.roomName}. Adjust surround level in Sound."
        is SetupTask.StereoPair -> "${task.roomName} is now a stereo pair."
        is SetupTask.RemoveSatellite -> "${task.satelliteLabel} is its own room again."
        is SetupTask.SeparatePair -> "The pair is separated. Both speakers are rooms again."
    }

    override fun play(device: SonosDevice): Boolean = playPause(device, true)
    override fun pause(device: SonosDevice): Boolean = playPause(device, false)

    override fun volume(device: SonosDevice): Int? = soap(
        device, "RenderingControl", "GetVolume",
        "<InstanceID>0</InstanceID><Channel>Master</Channel>",
    )?.let { Regex("<CurrentVolume>(\\d+)</CurrentVolume>").find(it)?.groupValues?.get(1)?.toInt() }

    override fun setVolume(device: SonosDevice, value: Int): Boolean {
        require(value in 0..100) { "volume must be 0..100" }
        return soap(device, "RenderingControl", "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$value</DesiredVolume>") != null
    }

    fun playPause(device: SonosDevice, play: Boolean): Boolean {
        val target = transportTarget(device)
        val action = if (play) "Play" else "Pause"
        val available = soap(target, "AVTransport", "GetCurrentTransportActions", "<InstanceID>0</InstanceID>")
            ?.let(SonosProtocol::transportActions).orEmpty()
        if (available.isNotEmpty() && action !in available) {
            lastCommandError = if (play) "This source has nothing resumable" else "This source cannot be paused"
            return false
        }
        val arguments = "<InstanceID>0</InstanceID>" + if (play) "<Speed>1</Speed>" else ""
        return soap(target, "AVTransport", action, arguments) != null
    }

    /** Changes only the coordinator, then reads it back; never writes bonded-zone membership. */
    fun switchToTv(coordinator: SonosDevice): Boolean {
        val uri = SonosProtocol.tvUri(coordinator.uid)
        if (soap(coordinator, "AVTransport", "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${escape(uri)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>") == null) return false
        val media = soap(coordinator, "AVTransport", "GetMediaInfo", "<InstanceID>0</InstanceID>") ?: return false
        return Regex("<CurrentURI>(.*?)</CurrentURI>").find(media)?.groupValues?.get(1) == uri
    }

    /** Confirms the stable room UID at its current address, rediscovers only when stale, then reads the TV URI back. */
    fun switchToTvAndVerify(coordinator: SonosDevice): SonosDevice? {
        val current = resolve(coordinator) ?: return null
        return current.takeIf(::switchToTv)
    }

    /** Serialize recovery across entry points; every IO step uses the resolved soundbar. */
    @Synchronized
    override fun quickConnect(storedTarget: SonosDevice): QuickConnectOutcome = QuickConnectTransaction.run(
        storedTarget,
        resolve = ::resolve,
        topology = ::zoneState,
        selectTv = { target ->
            val uri = SonosProtocol.tvUri(target.uid)
            soap(target, "AVTransport", "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>${escape(uri)}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>") != null
        },
        readUri = { target ->
            soap(target, "AVTransport", "GetMediaInfo", "<InstanceID>0</InstanceID>")
                ?.let { SonosProtocol.soapText(it, "CurrentURI") }
        },
    )

    private fun resolve(device: SonosDevice): SonosDevice? = readDescription(device)
        ?.let { SonosProtocol.deviceFromDescription(it, device.ip) }
        ?.takeIf { it.uid == device.uid }
        ?: discover().firstOrNull { it.uid == device.uid }

    private fun transportTarget(device: SonosDevice): SonosDevice {
        val location = zoneState(device)?.let { SonosProtocol.coordinatorLocation(it, device.uid) } ?: return device
        val ip = runCatching { URI(location).host }.getOrNull() ?: return device
        if (ip == device.ip) return device
        return readDescription(location)?.let { SonosProtocol.deviceFromDescription(it, ip) } ?: device
    }

    private fun soap(device: SonosDevice, service: String, action: String, arguments: String): String? {
        if (!action.startsWith("Get")) lastCommandError = ""
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"urn:schemas-upnp-org:service:$service:1\">$arguments</u:$action></s:Body></s:Envelope>"
        return request("POST", "http://${device.ip}:1400${SonosProtocol.servicePath(service)}", body, "urn:schemas-upnp-org:service:$service:1#$action")
    }

    private fun request(method: String, endpoint: String, body: String?, soapAction: String?): String? {
        if (!SonosProtocol.isLocalSonosEndpoint(endpoint)) {
            if (method == "POST") lastCommandError = "Blocked an unsafe Sonos endpoint"
            return null
        }
        val network = wifiNetwork() ?: run {
            if (method == "POST") lastCommandError = "This phone has no usable Wi-Fi route"
            return null
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (network.openConnection(URL(endpoint)) as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                instanceFollowRedirects = false
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                    setRequestProperty("SOAPACTION", "\"$soapAction\"")
                }
            }
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() }.orEmpty()
            if (status !in 200..299) {
                if (method == "POST") lastCommandError = SonosProtocol.upnpCommandError(response) ?: "Sonos returned HTTP $status"
                null
            } else response
        } catch (error: Exception) {
            if (method == "POST") lastCommandError = "Could not reach Sonos (${error.javaClass.simpleName})"
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private companion object { const val SSDP_HOST = "239.255.255.250"; const val SSDP_PORT = 1900 }
}
