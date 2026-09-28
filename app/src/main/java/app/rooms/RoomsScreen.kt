package app.rooms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rooms.sonos.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Home: one card per *group* (not per speaker). Rooms playing together are shown together,
 * so you never have to work out which room is really in control.
 */
@Composable
internal fun RoomsScreen(
    client: SonosController,
    system: SonosSystem,
    members: List<SonosZoneMember>,
    message: String,
    discovering: Boolean,
    refresh: () -> Unit,
    quickTargetUid: String?,
    quickBusy: Boolean,
    quickStatus: String,
    selectQuickTarget: (SonosDevice) -> Unit,
    quickConnect: () -> Unit,
    openRoom: (SonosDevice) -> Unit,
    roomDetail: (SonosDevice) -> Unit,
    addSpeakers: () -> Unit,
) {
    var summaries by remember { mutableStateOf<Map<String, SonosRoomState>>(emptyMap()) }
    LaunchedEffect(system.rooms.map { it.uid }) {
        while (true) {
            system.rooms.forEach { room ->
                runCatching { withContext(Dispatchers.IO) { client.getRoomState(room) } }.onSuccess { summaries = summaries + (room.uid to it) }
            }
            delay(8_000)
        }
    }
    val byUid = system.rooms.associateBy { it.uid }
    val devices = system.products.associate { it.device.uid to it.device } + byUid
    val setups = SonosSetup.roomSetups(members, devices).associateBy { it.main.uid }
    val models = devices.mapValues { it.value.modelName }
    // Group visible rooms by coordinator; fall back to one card per room before topology arrives.
    // A Sub on its own can't play anything, so it isn't a room: it's a speaker waiting to be set up.
    val spareSubs = system.rooms.filter { speakerKind(it.modelName) == SpeakerKind.SUB }
    val playable = system.rooms - spareSubs.toSet()
    val groups: List<List<SonosDevice>> = if (members.isEmpty()) playable.map { listOf(it) } else {
        val order = playable.map { it.uid }
        playable.groupBy { room -> members.firstOrNull { it.uid == room.uid }?.groupCoordinator ?: room.uid }
            .map { (coordinator, rooms) -> rooms.sortedBy { if (it.uid == coordinator) -1 else order.indexOf(it.uid) } }
    }

    Column(
        Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wordmark(Modifier.weight(1f))
            IconButton(onClick = addSpeakers, modifier = Modifier.size(48.dp).described("Add or arrange speakers")) { PlusGlyph(22.dp, Ink) }
            if (system.rooms.isNotEmpty()) IconButton(onClick = refresh, enabled = !discovering, modifier = Modifier.size(48.dp).described("Search for speakers again")) {
                if (discovering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Ink) else RefreshGlyph(22.dp, Ink)
            }
        }
        Text("Rooms", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        if (system.rooms.isEmpty()) {
            EmptySystem(message, discovering, refresh)
            return@Column
        }
        groups.forEach { group ->
            GroupCard(client, group, summaries, setups, models, openRoom, roomDetail)
        }
        spareSubs.forEach { SpareSpeakerCard(it, addSpeakers) }
        QuickConnectCard(playable, quickTargetUid, quickBusy, quickStatus, selectQuickTarget, quickConnect)
    }
}

@Composable
private fun EmptySystem(message: String, discovering: Boolean, refresh: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SettingCard {
        CardTitle("No speakers found")
        Text("Your phone and speakers need to be on the same home Wi-Fi.", color = Muted)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Join your home Wi-Fi (not a guest network).",
                "Turn off any VPN.",
                "Check the speakers have power.",
            ).forEachIndexed { i, tip ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("${i + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
                    Text(tip, modifier = Modifier.weight(1f))
                }
            }
        }
        PrimaryButton(if (discovering) "Searching…" else "Search again", enabled = !discovering, onClick = refresh)
        SecondaryButton("Open Wi-Fi settings") {
            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        Text("Details: $message", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun GroupCard(
    client: SonosController,
    rooms: List<SonosDevice>,
    summaries: Map<String, SonosRoomState>,
    setups: Map<String, RoomSetup>,
    models: Map<String, String>,
    openRoom: (SonosDevice) -> Unit,
    roomDetail: (SonosDevice) -> Unit,
) {
    val lead = rooms.first()
    val state = summaries[lead.uid]
    val playing = state?.transport == "PLAYING"
    val title = state?.title?.takeIf { it.isNotBlank() } ?: SonosProtocol.sourceLabel(state?.source.orEmpty()).let { if (it == "Idle") "Nothing playing" else it }
    val sub = joinText(state?.artist, null)
    val name = rooms.joinToString(" + ") { it.roomName.ifBlank { it.modelName } }
    Card(
        Modifier.fillMaxWidth().clickable { openRoom(lead) }.semantics { role = Role.Button; contentDescription = "$name. $title. Open player" },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Rule),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(client, state?.artworkUri.orEmpty(), lead.modelName, Modifier.size(72.dp), corner = 12.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOfNotNull(sub.takeIf(String::isNotBlank), transportLabel(state?.transport).takeIf { state != null && it != "Idle" }, state?.volume?.let { "Vol $it" })
                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Speaker make-up per room, with a direct route to that room's settings.
            HorizontalDivider(color = Rule)
            rooms.forEach { room ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (rooms.size > 1) Text(room.roomName, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(setups[room.uid]?.summary(models) ?: room.modelName, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { roomDetail(room) }, modifier = Modifier.size(48.dp).described("${room.roomName} settings")) { SlidersGlyph(20.dp, Muted) }
                }
            }
        }
    }
}

@Composable
private fun SpareSpeakerCard(speaker: SonosDevice, setUp: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = setUp).semantics { role = Role.Button; contentDescription = "${speaker.modelName} not set up yet. Set it up" },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Rule),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(speaker.modelName, Modifier.size(72.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(speaker.modelName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Not in a room yet", color = Muted, fontSize = 13.sp)
                Text("Add it to a room", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            ChevronRight(18.dp, Muted)
        }
    }
}

@Composable
private fun QuickConnectCard(
    rooms: List<SonosDevice>, targetUid: String?, busy: Boolean, status: String,
    selectTarget: (SonosDevice) -> Unit, connect: () -> Unit,
) {
    val supported = rooms.filter { SonosProtocol.supportsTvAudio(it.modelName) }
    if (supported.isEmpty()) return
    var chooserOpen by remember { mutableStateOf(false) }
    val target = targetUid?.let { uid -> rooms.firstOrNull { it.uid == uid } }
    SettingCard {
        CardTitle("TV sound not working?")
        Text("Switches the soundbar back to the TV input and checks it answered.", color = Muted)
        OutlinedButton(
            onClick = { chooserOpen = true }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            border = BorderStroke(1.dp, if (target == null) Ink else Rule), shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Soundbar", color = Muted, fontSize = 12.sp)
                Text(target?.let { "${it.roomName.ifBlank { "Unnamed room" }} · ${it.modelName}" } ?: if (targetUid != null) "Selected soundbar unavailable" else "Choose soundbar",
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
            }
            ChevronGlyph(expanded = false, size = 20.dp, color = Ink)
        }
        Button(onClick = connect, enabled = target != null && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).described("Reconnect TV sound"), shape = RoundedCornerShape(14.dp)) {
            Text(if (busy) "Reconnecting…" else "Reconnect TV sound", fontWeight = FontWeight.SemiBold)
        }
        if (status.isNotBlank()) {
            val failed = status.startsWith("Could not")
            Text(status, color = if (failed) Danger else Muted, fontSize = 13.sp)
        }
    }
    if (chooserOpen) AlertDialog(
        onDismissRequest = { chooserOpen = false }, title = { Text("Choose soundbar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                supported.forEach { room ->
                    ChoiceRow(room.roomName.ifBlank { "Unnamed room" }, room.modelName, room.uid == targetUid, image = room.modelName) { selectTarget(room); chooserOpen = false }
                }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { chooserOpen = false }) { Text("Cancel") } },
    )
}
