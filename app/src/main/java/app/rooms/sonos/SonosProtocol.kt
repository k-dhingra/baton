package app.rooms.sonos

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** The small amount of Sonos state the phone needs to identify a room. */
data class SonosDevice(
    val uid: String,
    val roomName: String,
    val modelName: String,
    val ip: String,
    val modelNumber: String = ""
)

data class SonosNowPlaying(
    val title: String = "", val artist: String = "", val album: String = "",
    val trackUri: String = "", val artworkUri: String = "", val positionSeconds: Int? = null,
    val durationSeconds: Int? = null
)

data class SonosDidlItem(
    val id: String,
    val title: String,
    val uri: String?,
    val artist: String = "",
    val album: String = "",
    val artworkUri: String = "",
    val description: String = "",
    val metadata: String = "",
    val upnpClass: String = "",
    val type: String = "",
)

/** One ZoneGroupMember or Satellite from the topology, flattened for setup decisions. */
data class SonosZoneMember(
    val uid: String,
    val zoneName: String,
    val groupCoordinator: String,
    val groupSize: Int,
    val invisible: Boolean,
    val satellite: Boolean,
    val htSatChanMapSet: String,
    val channelMapSet: String,
)

sealed class FavouritePlayback {
    data class Direct(val uri: String, val metadata: String) : FavouritePlayback()
    data class Queue(val uri: String, val metadata: String) : FavouritePlayback()
    data class Unsupported(val reason: String) : FavouritePlayback()
}

sealed class SonosGroupAction {
    data class Join(val uid: String, val coordinatorUid: String) : SonosGroupAction()
    data class Leave(val uid: String) : SonosGroupAction()
}

enum class SonosProductKind {
    ARC, ARC_ULTRA, BEAM, ERA_100, SUB, SUB_MINI, UNKNOWN;
    companion object {
        fun fromModel(label: String): SonosProductKind {
            val model = label.lowercase()
            return when {
                "arc ultra" in model || "arcultra" in model -> ARC_ULTRA
                "arc" in model -> ARC
                "beam" in model -> BEAM
                "era 100" in model || "era100" in model -> ERA_100
                "sub mini" in model || "submini" in model -> SUB_MINI
                Regex("\\bsub\\b").containsMatchIn(model) -> SUB
                else -> UNKNOWN
            }
        }
    }
}

enum class SonosProductRole { COORDINATOR, SURROUND, SUB, OTHER }

data class SonosProduct(
    val device: SonosDevice,
    val isRoomCoordinator: Boolean,
    val role: SonosProductRole = if (isRoomCoordinator) SonosProductRole.COORDINATOR else SonosProductRole.OTHER,
    val inferred: Boolean = false,
) {
    val isRoutable: Boolean get() = !inferred && device.ip.isNotBlank()
}

data class SonosSystem(val rooms: List<SonosDevice>, val products: List<SonosProduct>) {
    companion object {
        fun from(discovered: List<SonosDevice>, coordinatorUids: Set<String>) = SonosSystem(
            discovered.filter { it.uid in coordinatorUids },
            discovered.map { SonosProduct(it, it.uid in coordinatorUids) },
        )
    }
}

data class ViewerPoint(val x: Float, val y: Float, val z: Float)
data class ViewerProjection(val x: Float, val y: Float)

object ViewerMath {
    fun clampZoom(value: Float) = value.coerceIn(1f, 3f)
    fun clampPitch(value: Float) = value.coerceIn(-1.1f, 1.1f)
    fun project(point: ViewerPoint, yaw: Float, pitch: Float, zoom: Float): ViewerProjection {
        val cy = kotlin.math.cos(yaw); val sy = kotlin.math.sin(yaw)
        val cp = kotlin.math.cos(pitch); val sp = kotlin.math.sin(pitch)
        val x = point.x * cy - point.z * sy
        val z = point.x * sy + point.z * cy
        val y = point.y * cp - z * sp
        val depth = (point.y * sp + z * cp + 4f).coerceAtLeast(.1f)
        return ViewerProjection(x * zoom / depth, y * zoom / depth)
    }
}

data class SonosTheatre(
    val roomName: String,
    val coordinatorUid: String,
    val surroundUids: Set<String>,
    val subUid: String?,
)

sealed class QuickConnectPreflight {
    data class Ready(val configuredMembers: Set<String>) : QuickConnectPreflight()
    data class Refused(val reason: String) : QuickConnectPreflight()
}

sealed class QuickConnectVerification {
    data object Verified : QuickConnectVerification()
    data class Failed(val reason: String) : QuickConnectVerification()
}

object SonosProtocol {
    fun quickConnectPreflight(target: SonosDevice, rooms: List<SonosDevice>, zoneStateXml: String): QuickConnectPreflight {
        if (rooms.none { it.uid == target.uid }) return QuickConnectPreflight.Refused("The selected device is unavailable")
        if (!supportsTvAudio(target.modelName)) return QuickConnectPreflight.Refused("Choose a device with a TV audio input")
        val group = parse(zoneStateXml)?.all("ZoneGroup")?.firstOrNull { it.attr("Coordinator") == target.uid }
            ?: return QuickConnectPreflight.Refused("Home theatre topology is unavailable")
        val groupMembers = group.all("ZoneGroupMember").mapNotNull { it.attr("UUID").takeIf(String::isNotBlank) }.toSet()
        if (groupMembers != setOf(target.uid)) return QuickConnectPreflight.Refused("Un-group the selected device before switching TV audio")
        val member = group.all("ZoneGroupMember").firstOrNull { it.attr("UUID") == target.uid }
            ?: return QuickConnectPreflight.Refused("Home theatre topology is unavailable")
        val configured = parseBondedChannels(member.attr("HTSatChanMapSet")).keys + target.uid
        return QuickConnectPreflight.Ready(configured)
    }


    fun verifyQuickConnect(targetUid: String, readbackUri: String, beforeMembers: Set<String>, afterMembers: Set<String>): QuickConnectVerification {
        if (readbackUri != tvUri(targetUid)) return QuickConnectVerification.Failed("TV input selection could not be verified for this device")
        if (beforeMembers != afterMembers) return QuickConnectVerification.Failed("Configured theatre members changed")
        return QuickConnectVerification.Verified
    }

    fun locationFromSsdp(packet: String): String? = packet.lineSequence()
        .firstOrNull { it.substringBefore(':', "").trim().equals("location", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }

    fun deviceFromDescription(xml: String, ip: String): SonosDevice? {
        val root = parse(xml) ?: return null
        val device = root.first("device") ?: return null
        if (device.firstText("manufacturer") != "Sonos, Inc." ||
            device.firstText("deviceType") != "urn:schemas-upnp-org:device:ZonePlayer:1"
        ) return null
        val uid = device.firstText("UDN")?.removePrefix("uuid:")?.trim().orEmpty()
        if (uid.isEmpty()) return null
        return SonosDevice(uid, device.firstText("roomName").orEmpty(), device.firstText("modelName").orEmpty(), ip, device.firstText("modelNumber").orEmpty())
    }

    fun theatreFromZoneState(xml: String, coordinatorUid: String): SonosTheatre? {
        val root = parse(xml) ?: return null
        val group = root.all("ZoneGroup").firstOrNull { it.attr("Coordinator") == coordinatorUid } ?: return null
        val coordinator = group.all("ZoneGroupMember").firstOrNull { it.attr("UUID") == coordinatorUid }
            ?: return null
        val surrounds = linkedSetOf<String>()
        var sub: String? = null
        coordinator.attr("HTSatChanMapSet").split(';').forEach { mapping ->
            val uid = mapping.substringBefore(':').trim()
            val channels = mapping.substringAfter(':', "").split(',').map { it.trim() }.toSet()
            if (uid.isNotEmpty() && channels.any { it in setOf("LR", "RR", "LTR", "RTR") }) surrounds += uid
            if (uid.isNotEmpty() && "SW" in channels) sub = uid
        }
        return SonosTheatre(coordinator.attr("ZoneName"), coordinatorUid, surrounds, sub)
    }

    fun inventoryProducts(discovered: List<SonosDevice>, zoneStateXml: String?): List<SonosProduct> {
        val coordinators = coordinatorUidsForInventory(discovered, zoneStateXml)
        val exact = discovered.associateBy { it.uid }
        val inferred = linkedMapOf<String, SonosProduct>()
        if (!zoneStateXml.isNullOrBlank()) {
            parse(zoneStateXml)?.all("ZoneGroup").orEmpty().forEach { group ->
                group.all("ZoneGroupMember").firstOrNull { it.attr("UUID") in coordinators }?.let { coordinator ->
                    parseBondedChannels(coordinator.attr("HTSatChanMapSet")).forEach { (uid, role) ->
                        if (uid !in exact && uid !in inferred) {
                            val model = if (role == SonosProductRole.SUB) "Sonos Sub" else "Sonos Era 100"
                            inferred[uid] = SonosProduct(SonosDevice(uid, coordinator.attr("ZoneName"), model, ""), false, role, true)
                        }
                    }
                }
            }
        }
        return (discovered.map { device ->
            SonosProduct(device, device.uid in coordinators, when {
                device.uid in coordinators -> SonosProductRole.COORDINATOR
                SonosProductKind.fromModel(device.modelName) == SonosProductKind.SUB -> SonosProductRole.SUB
                SonosProductKind.fromModel(device.modelName) == SonosProductKind.ERA_100 -> SonosProductRole.SURROUND
                else -> SonosProductRole.OTHER
            })
        } + inferred.values).distinctBy { it.device.uid }
    }

    private fun parseBondedChannels(value: String): Map<String, SonosProductRole> = value.split(';').mapNotNull { mapping ->
        val uid = mapping.substringBefore(':').trim()
        val channels = mapping.substringAfter(':', "").split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }
        if (uid.isBlank() || channels.isEmpty()) null
        else when {
            channels.any { it in setOf("LR", "RR", "LTR", "RTR") } -> uid to SonosProductRole.SURROUND
            channels.any { it == "SW" } -> uid to SonosProductRole.SUB
            else -> null
        }
    }.toMap()
    fun isTvSource(uri: String): Boolean = uri.startsWith("x-sonos-htastream:", ignoreCase = true)

    fun tvUri(coordinatorUid: String): String = "x-sonos-htastream:$coordinatorUid:spdif"

    fun supportsTvAudio(modelName: String): Boolean {
        val model = modelName.lowercase()
        return listOf("arc", "beam", "ray", "playbar", "playbase", "sonos amp").any(model::contains)
    }

    fun zoneStateFromSoap(responseXml: String): String? = parse(responseXml)
        ?.firstText("ZoneGroupState")
        ?.takeIf { it.isNotBlank() }

    fun coordinatorUids(zoneStateXml: String): Set<String> = parse(zoneStateXml)
        ?.all("ZoneGroup")
        ?.mapNotNull { it.attr("Coordinator").takeIf(String::isNotBlank) }
        ?.toSet()
        .orEmpty()

    fun visibleRoomUids(zoneStateXml: String): Set<String> {
        val root = parse(zoneStateXml) ?: return emptySet()
        val members = root.all("ZoneGroupMember")
        val bonded = members.flatMap { member ->
            member.attr("HTSatChanMapSet").split(';').mapNotNull { mapping ->
                val uid = mapping.substringBefore(':').trim()
                val channels = mapping.substringAfter(':', "").split(',').map(String::trim)
                uid.takeIf { it.isNotBlank() && channels.none { channel -> channel in setOf("LF", "RF") } }
            }
        }.toSet()
        return members.mapNotNull { member ->
            member.attr("UUID").takeIf {
                it.isNotBlank() && member.attr("Invisible") != "1" && it !in bonded
            }
        }.toSet()
    }

    fun productLocations(zoneStateXml: String): Set<String> = parse(zoneStateXml)
        ?.all("ZoneGroupMember").orEmpty().plus(parse(zoneStateXml)?.all("Satellite").orEmpty())
        .mapNotNull { it.attr("Location").takeIf { location -> isLocalSonosLocation(location) } }
        .toSet()

    fun coordinatorUidsForInventory(devices: List<SonosDevice>, zoneStateXml: String?): Set<String> {
        if (!zoneStateXml.isNullOrBlank()) return visibleRoomUids(zoneStateXml)
        return devices.filter { it.roomName.isNotBlank() && SonosProductKind.fromModel(it.modelName) !in setOf(SonosProductKind.SUB, SonosProductKind.SUB_MINI) }
            .groupBy { it.roomName }
            .values.map { group -> group.firstOrNull { supportsTvAudio(it.modelName) } ?: group.first() }
            .map { it.uid }.toSet()
    }

    fun servicePath(service: String): String = when (service) {
        "AVTransport" -> "/MediaRenderer/AVTransport/Control"
        "RenderingControl" -> "/MediaRenderer/RenderingControl/Control"
        "GroupRenderingControl" -> "/MediaRenderer/GroupRenderingControl/Control"
        "ContentDirectory" -> "/MediaServer/ContentDirectory/Control"
        "ZoneGroupTopology" -> "/ZoneGroupTopology/Control"
        "DeviceProperties" -> "/DeviceProperties/Control"
        else -> error("Unsupported Sonos service: $service")
    }

    fun ssdpSearchQuery(searchTarget: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: $searchTarget\r\n\r\n"

    fun isLocalSonosEndpoint(endpoint: String): Boolean = runCatching {
        val uri = URI(endpoint)
        val host = uri.host ?: return@runCatching false
        if (uri.scheme != "http" || uri.port != 1400 || uri.userInfo != null) return@runCatching false
        if (!host.matches(Regex("[0-9.]+")) && !host.contains(':')) return@runCatching false
        InetAddress.getByName(host).let {
            !it.isLoopbackAddress && (it.isSiteLocalAddress || it.isLinkLocalAddress)
        }
    }.getOrDefault(false)

    fun isLocalSonosLocation(location: String): Boolean = runCatching {
        val uri = URI(location)
        if (uri.path != "/xml/device_description.xml") {
            return@runCatching false
        }
        isLocalSonosEndpoint(location)
    }.getOrDefault(false)

    fun localScanHosts(phoneIp: String, prefixLength: Int = 24): List<String> {
        val octets = phoneIp.split('.').mapNotNull(String::toLongOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return emptyList()
        // ponytail: cap fallback at /22 (1022 hosts); multicast remains the primary path on larger LANs.
        if (prefixLength !in 22..30) return emptyList()
        val address = octets.fold(0L) { value, octet -> (value shl 8) or octet }
        val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
        val network = address and mask
        val broadcast = network or (mask xor 0xffffffffL)
        return ((network + 1) until broadcast).asSequence()
            .filterNot { it == address }
            .map { value -> listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 255).toString() } }
            .toList()
    }

    fun discoveryMessage(
        replyCount: Int,
        sonosCount: Int,
        multicastFailure: String? = null,
        scanFailure: String? = null,
    ): String = when {
        scanFailure != null -> "No rooms · local scan failed ($scanFailure)"
        multicastFailure != null && sonosCount == 0 -> "No rooms · multicast blocked ($multicastFailure) · local scan found none"
        replyCount == 0 -> "No Sonos replies · multicast and local scan tried"
        sonosCount == 0 -> "$replyCount local replies · none identified as Sonos"
        else -> "$sonosCount Sonos room${if (sonosCount == 1) "" else "s"} found"
    }

    fun sourceLabel(uri: String): String = when {
        uri.isBlank() -> "Idle"
        isTvSource(uri) -> "TV"
        uri.startsWith("x-rincon-queue:", ignoreCase = true) -> "Queue"
        uri.startsWith("x-sonos-vli:", ignoreCase = true) -> "AirPlay"
        uri.startsWith("x-rincon-stream:", ignoreCase = true) -> "Line-in"
        uri.startsWith("x-sonosapi-stream:", ignoreCase = true) -> "Radio"
        else -> "Music"
    }

    fun nowPlayingFromPosition(response: String, speakerIp: String): SonosNowPlaying? {
        val root = parse(response) ?: return null
        val metadata = root.all("TrackMetaData").firstOrNull()?.textContent?.trim().orEmpty()
        val parsed = parseNowPlaying(metadata, root.all("RelTime").firstOrNull()?.textContent) ?: SonosNowPlaying()
        val uri = root.all("TrackURI").firstOrNull()?.textContent?.trim().orEmpty().ifBlank { parsed.trackUri }
        return parsed.copy(trackUri = uri, durationSeconds = durationSeconds(root.all("TrackDuration").firstOrNull()?.textContent) ?: parsed.durationSeconds,
            artworkUri = resolveArtwork(parsed.artworkUri, speakerIp).orEmpty())
    }

    fun soundAction(kind: String, set: Boolean): String = when (kind.lowercase()) {
        "bass" -> if (set) "SetBass" else "GetBass"
        "treble" -> if (set) "SetTreble" else "GetTreble"
        "loudness" -> if (set) "SetLoudness" else "GetLoudness"
        else -> error("unsupported sound control")
    }

    fun soundArguments(kind: String, value: Int): String = when (kind.lowercase()) {
        "bass" -> "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredBass>$value</DesiredBass>"
        "treble" -> "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredTreble>$value</DesiredTreble>"
        "loudness" -> "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredLoudness>$value</DesiredLoudness>"
        else -> error("unsupported sound control")
    }

    fun devicePropertyArguments(kind: String, enabled: Boolean): String = when (kind.lowercase()) {
        "led" -> "<DesiredLEDState>${if (enabled) "On" else "Off"}</DesiredLEDState>"
        "touch" -> "<DesiredButtonLockState>${if (enabled) "Off" else "On"}</DesiredButtonLockState>"
        else -> error("unsupported device property")
    }

    fun roomNameArguments(value: String): String? = value.trim().takeIf { it.isNotBlank() }?.let {
        "<DesiredZoneName>${xmlEscape(it)}</DesiredZoneName><DesiredIcon></DesiredIcon><DesiredConfiguration></DesiredConfiguration>"
    }

    fun eqArguments(type: String, value: Int): String? {
        val range = when (type) {
            "SurroundEnable", "SurroundMode", "NightMode", "DialogLevel" -> 0..1
            "SurroundLevel", "MusicSurroundLevel", "SubGain" -> -15..15
            "SubEnable" -> 0..1
            "AudioDelay" -> 0..5
            else -> return null
        }
        return value.takeIf { it in range }?.let {
            "<InstanceID>0</InstanceID><EQType>$type</EQType><DesiredValue>$it</DesiredValue>"
        }
    }

    fun parseOnOff(xml: String, tag: String): Boolean? = soapText(xml, tag)?.let {
        when (it.lowercase()) { "on" -> true; "off" -> false; else -> null }
    }

    fun transportActions(xml: String): Set<String> = parse(xml)?.all("CurrentTransportActions")?.firstOrNull()?.textContent
        ?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.toSet().orEmpty()

    fun upnpCommandError(xml: String): String? {
        val root = parse(xml) ?: return null
        val code = root.all("errorCode").firstOrNull()?.textContent?.trim().orEmpty()
        val description = root.all("errorDescription").firstOrNull()?.textContent?.trim().orEmpty()
        if (code.isBlank() && description.isBlank()) return null
        return when {
            description.isNotBlank() && code.isNotBlank() -> "$description (UPnP $code)"
            description.isNotBlank() -> description
            else -> "Sonos command failed (UPnP $code)"
        }
    }

    fun playbackUnavailableReason(source: String, actions: Set<String>): String? = when {
        isTvSource(source) -> "TV audio is controlled by your television"
        actions.isNotEmpty() && "Play" !in actions -> "This source has nothing resumable"
        else -> null
    }

    fun durationSeconds(value: String?): Int? = value?.trim()?.split(":")?.let { p -> when (p.size) {
        3 -> (p[0].toIntOrNull() ?: return@let null) * 3600 + (p[1].toIntOrNull() ?: return@let null) * 60 + (p[2].toIntOrNull() ?: return@let null)
        2 -> (p[0].toIntOrNull() ?: return@let null) * 60 + (p[1].toIntOrNull() ?: return@let null)
        1 -> p[0].toIntOrNull()
        else -> null
    } }

    fun resolveArtwork(value: String, speakerIp: String): String? = runCatching {
        val uri = URI(value)
        val resolved = if (!uri.isAbsolute) URI("http://$speakerIp:1400${if (value.startsWith("/")) value else "/$value"}") else uri
        resolved.takeIf { it.scheme.equals("http", true) && it.host == speakerIp && it.port == 1400 }?.toString()
    }.getOrNull()

    fun groupMembers(zoneStateXml: String, coordinatorUid: String): Set<String> = parse(zoneStateXml)?.all("ZoneGroup")
        ?.firstOrNull { it.attr("Coordinator") == coordinatorUid }?.all("ZoneGroupMember")
        ?.mapNotNull { it.attr("UUID").takeIf(String::isNotBlank) }?.toSet().orEmpty()

    fun coordinatorLocation(zoneStateXml: String, memberUid: String): String? {
        val group = parse(zoneStateXml)?.all("ZoneGroup")
            ?.firstOrNull { candidate -> candidate.all("ZoneGroupMember").any { it.attr("UUID") == memberUid } }
            ?: return null
        val coordinatorUid = group.attr("Coordinator")
        return group.all("ZoneGroupMember")
            .firstOrNull { it.attr("UUID") == coordinatorUid }
            ?.attr("Location")
            ?.takeIf(::isLocalSonosLocation)
    }

    fun groupPlan(zoneStateXml: String, desired: Map<String, String?>): List<SonosGroupAction> = desired.mapNotNull { (uid, coordinator) ->
        when {
            coordinator != null && uid !in groupMembers(zoneStateXml, coordinator) -> SonosGroupAction.Join(uid, coordinator)
            coordinator == null && uid in coordinatorUids(zoneStateXml) -> SonosGroupAction.Leave(uid)
            else -> null
        }
    }

    fun parseNowPlaying(didl: String, position: String? = null): SonosNowPlaying? {
        val item = parseDidlItems(didl).firstOrNull() ?: return null
        val root = parse(didl) ?: return null
        val node = root.all("item").firstOrNull() ?: return null
        fun text(name: String) = node.all(name).firstOrNull()?.textContent?.trim().orEmpty()
        val res = node.all("res").firstOrNull()
        val art = node.all("albumArtURI").firstOrNull()?.textContent?.trim().orEmpty()
        return SonosNowPlaying(text("title"), text("creator").ifBlank { text("artist") }, text("album"), item.uri.orEmpty(), art, durationSeconds(position), res?.attr("duration")?.let(::durationSeconds))
    }

    fun parseDidlItems(xml: String): List<SonosDidlItem> = parse(xml)?.let { root -> root.all("item") + root.all("container") }.orEmpty().map { item ->
        fun text(name: String) = item.all(name).firstOrNull()?.textContent?.trim().orEmpty()
        SonosDidlItem(
            id = item.attr("id"),
            title = text("title"),
            uri = text("res").takeIf { it.isNotBlank() },
            artist = text("creator").ifBlank { text("artist") },
            album = text("album"),
            artworkUri = text("albumArtURI"),
            description = text("description"),
            metadata = text("resMD"),
            upnpClass = text("class"),
            type = text("type"),
        )
    }

    /** Only replay known Sonos favourite transports; arbitrary speaker-reported URIs are not commands. */
    fun favouritePlayback(item: SonosDidlItem): FavouritePlayback {
        val uri = item.uri?.trim().orEmpty()
        if (uri.isBlank()) return FavouritePlayback.Unsupported("Starts only in the Sonos app")
        if (uri.length > 8192 || uri.any { it.isISOControl() }) return FavouritePlayback.Unsupported("Unsupported favourite link")
        val queueSchemes = listOf("x-rincon-cpcontainer:", "x-rincon-playlist:")
        val localQueueFile = uri.startsWith("file:///jffs/settings/savedqueues/", true) && !uri.contains("..")
        val directSchemes = listOf("x-sonosapi-stream:", "x-sonosapi-radio:", "x-sonosapi-hls:",
            "x-rincon-mp3radio:", "x-rincon-queue:")
        return when {
            localQueueFile || queueSchemes.any { uri.startsWith(it, true) } -> FavouritePlayback.Queue(uri, item.metadata)
            directSchemes.any { uri.startsWith(it, true) } -> FavouritePlayback.Direct(uri, item.metadata)
            else -> FavouritePlayback.Unsupported("Open this favourite in the Sonos app")
        }
    }

    /** Flattens every ZoneGroupMember and nested Satellite, keeping group context. */
    fun zoneMembers(zoneStateXml: String): List<SonosZoneMember> {
        val root = parse(zoneStateXml) ?: return emptyList()
        return root.all("ZoneGroup").flatMap { group ->
            val coordinator = group.attr("Coordinator")
            val visibleCount = group.all("ZoneGroupMember").count { it.attr("Invisible") != "1" }
            group.all("ZoneGroupMember").map { false to it }.plus(group.all("Satellite").map { true to it }).mapNotNull { (satellite, node) ->
                val uid = node.attr("UUID").takeIf(String::isNotBlank) ?: return@mapNotNull null
                SonosZoneMember(uid, node.attr("ZoneName"), coordinator, visibleCount, node.attr("Invisible") == "1", satellite,
                    node.attr("HTSatChanMapSet"), node.attr("ChannelMapSet"))
            }
        }.distinctBy { it.uid }
    }

    /** Coordinator and visible members of whichever group contains [uid]. */
    fun groupContaining(zoneStateXml: String, uid: String): Pair<String, List<String>>? {
        val group = parse(zoneStateXml)?.all("ZoneGroup")
            ?.firstOrNull { g -> g.all("ZoneGroupMember").any { it.attr("UUID") == uid } } ?: return null
        val members = group.all("ZoneGroupMember").filter { it.attr("Invisible") != "1" }.mapNotNull { it.attr("UUID").takeIf(String::isNotBlank) }
        return group.attr("Coordinator") to members
    }

    fun queueSubtitle(item: SonosDidlItem): String = listOf(item.artist, item.album).filter(String::isNotBlank).joinToString(" · ")

    fun soapText(xml: String, tag: String): String? = parse(xml)?.all(tag)?.firstOrNull()?.textContent?.trim()
    fun parseBooleanValue(xml: String, tag: String): Boolean? = parseIntValue(xml, tag)?.let { it != 0 }
    fun parseIntValue(xml: String, tag: String): Int? = Regex("<$tag(?:\\s[^>]*)?>(-?\\d+)</$tag>").find(xml)?.groupValues?.get(1)?.toIntOrNull()
    fun isSafeArtworkUri(value: String): Boolean = runCatching {
        val uri = URI(value); uri.scheme.equals("http", true) && uri.port == 1400 && isLocalSonosLocation("http://${uri.host}:1400/xml/device_description.xml")
    }.getOrDefault(false)
    fun groupJoinUri(coordinatorUid: String) = "x-rincon:$coordinatorUid"
    fun xmlText(value: String) = xmlEscape(value)
    const val groupLeaveAction = "BecomeCoordinatorOfStandaloneGroup"

    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun parse(xml: String): Element? {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return null
        return runCatching {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // Android XML implementations differ; declarations are rejected above, so unsupported hardening flags may safely be skipped.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { isXIncludeAware = false }
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())).documentElement
        }.getOrNull()
    }

    private fun Element.first(name: String): Element? = all(name).firstOrNull()
    private fun Element.firstText(name: String): String? = first(name)?.textContent?.trim()
    private fun Element.all(name: String): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(node: Node) {
            if (node is Element && (node.localName == name || node.tagName == name)) result += node
            val children = node.childNodes
            for (i in 0 until children.length) visit(children.item(i))
        }
        visit(this)
        return result
    }
    private fun Element.attr(name: String): String = getAttribute(name).trim()
}
