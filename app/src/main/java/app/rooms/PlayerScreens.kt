package app.rooms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rooms.sonos.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun NowPlayingScreen(
    client: SonosController,
    system: SonosSystem,
    members: List<SonosZoneMember>,
    chosen: SonosDevice?,
    state: SonosRoomState?,
    stale: Boolean,
    choose: (SonosDevice) -> Unit,
    update: (SonosRoomState) -> Unit,
    queue: () -> Unit,
    favourites: () -> Unit,
    group: () -> Unit,
    sound: () -> Unit,
) {
    val room = chosen ?: system.rooms.firstOrNull()
    var working by remember(room?.uid) { mutableStateOf(false) }
    var error by remember(room?.uid) { mutableStateOf("") }
    var volume by remember(room?.uid) { mutableFloatStateOf((state?.volume ?: 0).toFloat()) }
    var committedVolume by remember(room?.uid) { mutableIntStateOf(state?.volume ?: 0) }
    var dragging by remember(room?.uid) { mutableStateOf(false) }
    var mute by remember(room?.uid) { mutableStateOf(state?.mute ?: false) }
    var actions by remember(room?.uid) { mutableStateOf<Set<String>>(emptySet()) }
    var seekDrag by remember(room?.uid) { mutableStateOf<Float?>(null) }
    var roomMenuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val coordinator = room?.let { r -> members.firstOrNull { it.uid == r.uid }?.groupCoordinator }
    val groupRooms = if (coordinator == null) listOfNotNull(room) else
        system.rooms.filter { r -> members.any { it.uid == r.uid && it.groupCoordinator == coordinator && !it.invisible } }
    val grouped = groupRooms.size > 1

    LaunchedEffect(state?.volume, state?.mute) {
        state?.volume?.let { committedVolume = it; if (!working && !dragging) volume = it.toFloat() }
        state?.mute?.let { if (!working) mute = it }
    }
    LaunchedEffect(room?.uid, state?.transport, state?.source) {
        room?.let { actions = withContext(Dispatchers.IO) { runCatching { client.currentTransportActions(it) }.getOrDefault(emptySet()) } }
    }

    fun command(action: () -> Boolean, rollback: (() -> Unit)? = null) {
        val device = room ?: return
        if (working) return
        working = true
        error = ""
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { action() } }.getOrDefault(false)
            if (ok) runCatching { withContext(Dispatchers.IO) { client.getRoomState(device) } }.onSuccess(update).onFailure { error = "Done, but couldn't refresh the player" }
            else { rollback?.invoke(); error = client.lastCommandError.ifBlank { "The speaker didn't accept that" } }
            working = false
        }
    }

    ScreenColumn {
        // Room picker is the page title: you always know which room you're controlling.
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(enabled = system.rooms.size > 1) { roomMenuOpen = true }
                    .semantics { role = Role.Button; contentDescription = "Controlling ${room?.roomName.orEmpty()}. Change room" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (grouped) "PLAYING IN ${groupRooms.size} ROOMS" else "NOW PLAYING", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                    Text(if (grouped) groupRooms.joinToString(" + ") { it.roomName } else room?.roomName.orEmpty(),
                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (system.rooms.size > 1) {
                    Text("Change", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    ChevronGlyph(false, 20.dp, Ink)
                }
            }
            DropdownMenu(expanded = roomMenuOpen, onDismissRequest = { roomMenuOpen = false }) {
                system.rooms.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.roomName) },
                        onClick = { choose(candidate); roomMenuOpen = false },
                        trailingIcon = { if (candidate.uid == room?.uid) CheckGlyph(18.dp, Ink) },
                    )
                }
            }
        }
        if (room == null) { Text("No room selected", color = Muted); return@ScreenColumn }
        if (stale) Text("Can't reach ${room.roomName} · showing last known state", color = Danger, fontSize = 13.sp)

        val isTv = SonosProtocol.isTvSource(state?.source.orEmpty())
        // Album art earns the space; a product photo doesn't.
        val art = state?.artworkUri.orEmpty()
        Artwork(client, art, room.modelName, if (art.isNotBlank()) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.fillMaxWidth().height(88.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(state?.title?.takeIf { it.isNotBlank() } ?: if (isTv) "TV sound" else "Nothing playing", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = joinText(state?.artist, state?.album).ifBlank { if (isTv) "" else SonosProtocol.sourceLabel(state?.source.orEmpty()) }
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        val duration = state?.durationSeconds ?: 0
        val canSeek = duration > 0 && (actions.isEmpty() || "Seek" in actions) && !isTv
        if (duration > 0) {
            val position = seekDrag?.toInt() ?: (state?.positionSeconds ?: 0)
            Slider(
                value = (seekDrag ?: (state?.positionSeconds ?: 0).toFloat()).coerceIn(0f, duration.toFloat()),
                onValueChange = { seekDrag = it },
                onValueChangeFinished = {
                    val target = seekDrag?.toInt() ?: return@Slider
                    command({ client.seek(room, target) })
                    seekDrag = null
                },
                valueRange = 0f..duration.toFloat(),
                enabled = canSeek && !working,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Track position" },
            )
            Row(Modifier.fillMaxWidth().offset(y = (-10).dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Muted, fontSize = 12.sp)
                Text(formatTime(duration), color = Muted, fontSize = 12.sp)
            }
        }

        val playing = state?.transport == "PLAYING"
        val unavailable = if (playing && actions.isNotEmpty() && "Pause" !in actions) "This source can't be paused"
            else if (!playing) SonosProtocol.playbackUnavailableReason(state?.source.orEmpty(), actions) else null
        // TV has no tracks: show no transport rather than three dead grey buttons.
        if (!isTv) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { command({ client.previous(room) }) }, enabled = !working && (actions.isEmpty() || "Previous" in actions),
                    modifier = Modifier.size(56.dp).described("Previous track")) { SkipGlyph(false, 26.dp, LocalContentColor.current) }
                PlaybackButton(playing, { command({ if (playing) client.pause(room) else client.play(room) }) }, !working && unavailable == null, Modifier.size(64.dp))
                IconButton(onClick = { command({ client.next(room) }) }, enabled = !working && (actions.isEmpty() || "Next" in actions),
                    modifier = Modifier.size(56.dp).described("Next track")) { SkipGlyph(true, 26.dp, LocalContentColor.current) }
            }
            if (unavailable != null) Text(unavailable, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        if (grouped) GroupVolume(client, room, groupRooms)
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(
                checked = mute,
                onCheckedChange = { target -> val old = mute; mute = target; command({ client.setMute(room, target) }, rollback = { mute = old }) },
                enabled = !working,
                modifier = Modifier.size(48.dp).semantics {
                    role = Role.Button; selected = mute
                    stateDescription = if (mute) "Muted" else "Sound on"
                    contentDescription = if (mute) "Unmute ${room.roomName}" else "Mute ${room.roomName}"
                },
                colors = IconButtonDefaults.iconToggleButtonColors(checkedContainerColor = Ink, checkedContentColor = androidx.compose.ui.graphics.Color.White),
            ) { SpeakerGlyph(mute, 22.dp, LocalContentColor.current) }
            Slider(
                value = volume,
                onValueChange = { dragging = true; volume = it },
                onValueChangeFinished = {
                    dragging = false
                    val target = volume.toInt()
                    command({ client.setVolume(room, target) }, rollback = { volume = committedVolume.toFloat() })
                },
                valueRange = 0f..100f, enabled = !working,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Volume for ${room.roomName}" },
            )
            Text("${volume.toInt()}", modifier = Modifier.width(34.dp), textAlign = TextAlign.End, color = Muted, fontSize = 13.sp)
        }
        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 13.sp)

        HorizontalDivider(color = Rule)
        SectionLabel("Music")
        NavigationRow("Favourites", "Your saved stations, playlists and albums", favourites)
        NavigationRow("Queue", "What's up next", queue)
        SectionLabel("Room")
        if (groupable(system).size > 1) NavigationRow("Play in other rooms", if (grouped) "Playing in ${groupRooms.size} rooms" else "Add rooms to play the same music", group)
        NavigationRow("Sound", "Bass, treble, TV and surround settings", sound)
    }
}

/** One master slider for the group plus a slider per room — what the Sonos app hides behind a long-press. */
@Composable
private fun GroupVolume(client: SonosController, room: SonosDevice, rooms: List<SonosDevice>) {
    var master by remember(rooms.map { it.uid }) { mutableFloatStateOf(0f) }
    var perRoom by remember(rooms.map { it.uid }) { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var draggingUid by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        val values = withContext(Dispatchers.IO) {
            val g = runCatching { client.groupVolume(room) }.getOrNull()
            g to rooms.associate { r -> r.uid to (runCatching { client.volume(r) }.getOrNull() ?: 0).toFloat() }
        }
        if (draggingUid == null) { values.first?.let { master = it.toFloat() }; perRoom = values.second }
    }
    LaunchedEffect(rooms.map { it.uid }) { while (true) { reload(); kotlinx.coroutines.delay(4_000) } }

    fun apply(action: () -> Boolean) {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { action() } }.getOrDefault(false)
            if (!ok) error = client.lastCommandError.ifBlank { "Volume wasn't changed" }
            reload(); busy = false
        }
    }

    SettingCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All rooms", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${master.toInt()}", color = Muted, fontSize = 13.sp)
        }
        Slider(master, { draggingUid = "*"; master = it }, onValueChangeFinished = {
            draggingUid = null; val target = master.toInt(); apply { client.setGroupVolume(room, target) }
        }, valueRange = 0f..100f, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Group volume" })
        HorizontalDivider(color = Rule)
        rooms.forEach { r ->
            val value = perRoom[r.uid] ?: 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.roomName, modifier = Modifier.width(110.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Slider(value, { draggingUid = r.uid; perRoom = perRoom + (r.uid to it) }, onValueChangeFinished = {
                    draggingUid = null; val target = (perRoom[r.uid] ?: 0f).toInt(); apply { client.setVolume(r, target) }
                }, valueRange = 0f..100f, enabled = !busy, modifier = Modifier.weight(1f).semantics { contentDescription = "Volume for ${r.roomName}" })
                Text("${value.toInt()}", modifier = Modifier.width(30.dp), textAlign = TextAlign.End, color = Muted, fontSize = 13.sp)
            }
        }
        if (error.isNotBlank()) Text(error, color = Danger, fontSize = 13.sp)
    }
}

@Composable
internal fun QueueScreen(client: SonosController, device: SonosDevice?, currentTrack: Int = 0, back: () -> Unit) {
    val currentIndex = currentTrack - 1
    var items by remember(device?.uid) { mutableStateOf<List<SonosDidlItem>>(emptyList()) }
    var loading by remember(device?.uid) { mutableStateOf(true) }
    var note by remember(device?.uid) { mutableStateOf(Note.None) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(device?.uid) {
        val current = device ?: run { loading = false; return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { client.queue(current) } }.onSuccess { items = it }.onFailure { note = Note.fail("Couldn't read the queue") }
        loading = false
    }
    ScreenColumn {
        BackHeader("Queue", back)
        device?.let { Text(it.roomName, color = Muted) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink)
        items.take(50).forEachIndexed { index, item ->
            val play: (() -> Unit)? = if (working || device == null) null else ({
                working = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { client.playQueueItem(device, index) }
                    note = if (ok) Note.ok("Playing ${item.title}") else Note.fail(client.lastCommandError.ifBlank { "Couldn't play that track" })
                    working = false
                }
            })
            val current = index == currentIndex
            NavigationRow(item.title.ifBlank { "Untitled" }, SonosProtocol.queueSubtitle(item), play, trailing = { PlaybackGlyph(false, 16.dp, Muted) }, emphasised = current) {
                if (current) PlaybackGlyph(false, 16.dp, Ink) else Text("${index + 1}", color = Muted, fontSize = 13.sp)
            }
        }
        if (!loading && items.isEmpty()) Text("The queue is empty. Start something from Favourites.", color = Muted)
        if (items.size > 50) Text("Showing the first 50 of ${items.size} tracks.", color = Muted, fontSize = 13.sp)
        NoteText(note)
    }
}

@Composable
internal fun FavouritesScreen(client: SonosController, device: SonosDevice?, played: () -> Unit, back: () -> Unit) {
    var items by remember(device?.uid) { mutableStateOf<List<SonosDidlItem>>(emptyList()) }
    var loading by remember(device?.uid) { mutableStateOf(true) }
    var note by remember(device?.uid) { mutableStateOf(Note.None) }
    var workingId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(device?.uid) {
        val current = device ?: run { loading = false; return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { client.favourites(current) } }.onSuccess { items = it }.onFailure { note = Note.fail("Couldn't read favourites") }
        loading = false
    }
    ScreenColumn {
        BackHeader("Favourites", back)
        device?.let { Text("Plays in ${it.roomName}", color = Muted) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink)
        items.forEach { item ->
            val plan = SonosProtocol.favouritePlayback(item)
            val playable = plan !is FavouritePlayback.Unsupported
            val subtitle = when {
                workingId == item.id -> "Starting…"
                !playable -> "${item.description.ifBlank { "Sonos shortcut" }} · opens in the Sonos app only"
                else -> item.description
            }
            val onClick: (() -> Unit)? = if (!playable || workingId != null || device == null) null else ({
                workingId = item.id
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { client.playFavourite(device, item) }.getOrDefault(false) }
                    workingId = null
                    if (ok) played() else note = Note.fail(client.lastCommandError.ifBlank { "Couldn't start ${item.title}" })
                }
            })
            NavigationRow(item.title.ifBlank { "Untitled" }, subtitle, onClick, trailing = { PlaybackGlyph(false, 16.dp, Muted) }) {
                Artwork(client, item.artworkUri, "", Modifier.size(40.dp), corner = 6.dp)
            }
        }
        if (!loading && items.isEmpty()) Text("No favourites yet. Add them in the Sonos app and they'll appear here.", color = Muted)
        NoteText(note)
    }
}

/** Rooms that can play audio. A lone Sub is excluded: it has nothing to play through. */
internal fun groupable(system: SonosSystem) = system.rooms.filter { speakerKind(it.modelName) != SpeakerKind.SUB }

/**
 * "Play in other rooms": the current room is fixed at the top; tick the rooms that should join it.
 * No coordinator jargon, one apply.
 */
@Composable
internal fun GroupScreen(client: SonosController, system: SonosSystem, members: List<SonosZoneMember>, selected: SonosDevice?, applied: () -> Unit, back: () -> Unit) {
    val anchor = selected ?: system.rooms.firstOrNull()
    val coordinatorUid = anchor?.let { a -> members.firstOrNull { it.uid == a.uid }?.groupCoordinator } ?: anchor?.uid.orEmpty()
    var chosen by remember(anchor?.uid) { mutableStateOf<Set<String>>(emptySet()) }
    var loaded by remember(anchor?.uid) { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(Note.None) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(anchor?.uid) {
        val a = anchor ?: return@LaunchedEffect
        val info = withContext(Dispatchers.IO) { runCatching { client.groupInfo(a) }.getOrNull() }
        chosen = info?.second?.toSet().orEmpty() - a.uid
        loaded = true
    }
    ScreenColumn {
        BackHeader("Play in other rooms", back)
        if (anchor == null) { Text("No room selected", color = Muted); return@ScreenColumn }
        Text("Tick rooms to play the same music as ${anchor.roomName}.", color = Muted)
        SettingCard {
            ChoiceRow(anchor.roomName, "This room · always included", selected = true, enabled = false, multi = true, image = anchor.modelName) {}
            HorizontalDivider(color = Rule)
            if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink)
            groupable(system).filter { it.uid != anchor.uid }.forEach { room ->
                val elsewhere = members.firstOrNull { it.uid == room.uid }?.groupCoordinator?.let { it != coordinatorUid && it != room.uid } == true
                ChoiceRow(room.roomName, if (elsewhere) "Currently playing with another room" else room.modelName,
                    selected = room.uid in chosen, enabled = !applying && loaded, multi = true, image = room.modelName) {
                    chosen = if (room.uid in chosen) chosen - room.uid else chosen + room.uid
                }
            }
        }
        val others = groupable(system).filter { it.uid != anchor.uid }
        if (others.size > 2) TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { chosen = if (chosen.size == others.size) emptySet() else others.map { it.uid }.toSet() }, enabled = !applying) {
            Text(if (chosen.size == others.size) "Untick all" else "Tick all")
        }
        val label = when {
            applying -> "Updating…"
            chosen.isEmpty() -> "Play only in ${anchor.roomName}"
            else -> "Play in ${chosen.size + 1} rooms"
        }
        PrimaryButton(label, enabled = !applying && loaded) {
            applying = true; note = Note.None
            scope.launch {
                // The anchor room leads unless it's currently following someone else — then it leads a fresh group.
                val result = withContext(Dispatchers.IO) {
                    if (coordinatorUid != anchor.uid) client.leaveGroup(anchor)
                    client.applyGroup(system.rooms, anchor.uid, chosen + anchor.uid)
                }
                applying = false
                applied()
                if (result.success) back() else note = Note.fail(result.message)
            }
        }
        NoteText(note)
    }
}
