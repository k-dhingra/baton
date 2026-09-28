package app.rooms

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rooms.sonos.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A labelled slider that reverts to the last confirmed value if the speaker rejects the change. */
@Composable
private fun LevelSlider(label: String, value: Int, range: IntRange, enabled: Boolean, steps: Int = 0, format: (Int) -> String = { if (it > 0) "+$it" else "$it" }, commit: (Int, onFail: () -> Unit) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Text(format(draft.toInt()), color = Muted, fontSize = 13.sp)
    }
    Slider(draft, { draft = it }, onValueChangeFinished = {
        val target = draft.toInt()
        if (target != value) commit(target) { draft = value.toFloat() }
    }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps, enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label })
}

/** Everything the Sound screen shows, read in one go off the main thread. */
private data class SoundSnapshot(
    val bass: Int, val treble: Int, val loudness: Boolean, val night: Boolean?, val dialog: Boolean?,
    val surroundEnabled: Boolean?, val surroundMode: Boolean?, val tvLevel: Int, val musicLevel: Int,
    val audioDelay: Int, val subEnabled: Boolean?, val subGain: Int?,
) {
    companion object {
        fun read(client: SonosController, d: SonosDevice) = SoundSnapshot(
            bass = client.bass(d) ?: 0, treble = client.treble(d) ?: 0, loudness = client.loudness(d) ?: false,
            night = client.soundbarMode(d, "NightMode"), dialog = client.soundbarMode(d, "DialogLevel"),
            surroundEnabled = client.eqValue(d, "SurroundEnable")?.let { it != 0 },
            surroundMode = client.eqValue(d, "SurroundMode")?.let { it != 0 },
            tvLevel = client.eqValue(d, "SurroundLevel") ?: 0, musicLevel = client.eqValue(d, "MusicSurroundLevel") ?: 0,
            audioDelay = client.eqValue(d, "AudioDelay") ?: 0,
            subEnabled = client.eqValue(d, "SubEnable")?.let { it != 0 }, subGain = client.eqValue(d, "SubGain"),
        )
    }
}

@Composable
internal fun SoundScreen(client: SonosController, device: SonosDevice?, members: List<SonosZoneMember>, back: () -> Unit) {
    var bass by remember(device?.uid) { mutableIntStateOf(0) }
    var treble by remember(device?.uid) { mutableIntStateOf(0) }
    var loudness by remember(device?.uid) { mutableStateOf(false) }
    var night by remember(device?.uid) { mutableStateOf<Boolean?>(null) }
    var dialog by remember(device?.uid) { mutableStateOf<Boolean?>(null) }
    var surroundEnabled by remember(device?.uid) { mutableStateOf<Boolean?>(null) }
    var surroundMode by remember(device?.uid) { mutableStateOf<Boolean?>(null) }
    var tvLevel by remember(device?.uid) { mutableIntStateOf(0) }
    var musicLevel by remember(device?.uid) { mutableIntStateOf(0) }
    var audioDelay by remember(device?.uid) { mutableIntStateOf(0) }
    var subEnabled by remember(device?.uid) { mutableStateOf<Boolean?>(null) }
    var subGain by remember(device?.uid) { mutableStateOf<Int?>(null) }
    var loading by remember(device?.uid) { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(Note.None) }
    val scope = rememberCoroutineScope()
    val setup = device?.let { d -> SonosSetup.roomSetups(members, mapOf(d.uid to d)).firstOrNull() }
    val hasSurrounds = setup?.surroundUids?.isNotEmpty() == true
    val hasSub = setup?.subUid != null

    LaunchedEffect(device?.uid) {
        val d = device ?: run { loading = false; return@LaunchedEffect }
        // Read on IO, publish on main: Compose state is never written from a background thread.
        val read = runCatching { withContext(Dispatchers.IO) { SoundSnapshot.read(client, d) } }.getOrNull()
        if (read == null) note = Note.fail("Couldn't read every setting") else {
            bass = read.bass; treble = read.treble; loudness = read.loudness; night = read.night; dialog = read.dialog
            surroundEnabled = read.surroundEnabled; surroundMode = read.surroundMode; tvLevel = read.tvLevel
            musicLevel = read.musicLevel; audioDelay = read.audioDelay; subEnabled = read.subEnabled; subGain = read.subGain
        }
        loading = false
    }

    fun change(action: () -> Boolean, onFail: () -> Unit, done: () -> Unit = {}) {
        if (working) return
        working = true; note = Note.None
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { action() } }.getOrDefault(false)
            if (ok) done() else { onFail(); note = Note.fail(client.lastCommandError.ifBlank { "Setting wasn't changed" }) }
            working = false
        }
    }

    ScreenColumn {
        BackHeader("Sound", back)
        if (device == null) { Text("No room selected", color = Muted); return@ScreenColumn }
        Text(device.roomName, color = Muted)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink)
        SettingCard {
            CardTitle("Tone")
            LevelSlider("Bass", bass, -10..10, !working && !loading) { t, fail -> change({ client.setBass(device, t) }, fail) { bass = t } }
            LevelSlider("Treble", treble, -10..10, !working && !loading) { t, fail -> change({ client.setTreble(device, t) }, fail) { treble = t } }
            SwitchRow("Loudness", "Fuller sound at low volume", loudness, !working) { t -> val old = loudness; loudness = t; change({ client.setLoudness(device, t) }, { loudness = old }) }
            SecondaryButton("Reset bass and treble", enabled = !working && (bass != 0 || treble != 0)) {
                change({ client.setBass(device, 0) && client.setTreble(device, 0) }, {}) { bass = 0; treble = 0 }
            }
        }
        if (night != null || dialog != null) SettingCard {
            CardTitle("TV")
            night?.let { v -> SwitchRow("Night sound", "Quieter explosions, clearer quiet bits", v, !working) { t -> night = t; change({ client.setSoundbarMode(device, "NightMode", t) }, { night = v }) } }
            dialog?.let { v -> SwitchRow("Speech enhancement", "Makes voices easier to hear", v, !working) { t -> dialog = t; change({ client.setSoundbarMode(device, "DialogLevel", t) }, { dialog = v }) } }
            LevelSlider("Lip sync delay", audioDelay, 0..5, !working && !loading, steps = 4, format = { if (it == 0) "Off" else "$it" }) { t, fail ->
                change({ client.setEqValue(device, "AudioDelay", t) }, fail) { audioDelay = t }
            }
            Text("Only raise this if voices arrive before lips move.", color = Muted, fontSize = 12.sp)
        }
        if (hasSub && (subEnabled != null || subGain != null)) SettingCard {
            CardTitle("Sub")
            subEnabled?.let { v -> SwitchRow("Sub on", "Switch off to hear the room without it", v, !working) { t -> subEnabled = t; change({ client.setEqValue(device, "SubEnable", if (t) 1 else 0) }, { subEnabled = v }) } }
            subGain?.let { g -> LevelSlider("Sub level", g, -15..15, !working && subEnabled != false) { t, fail -> change({ client.setEqValue(device, "SubGain", t) }, fail) { subGain = t } } }
        }
        if (hasSurrounds && surroundEnabled != null) SettingCard {
            CardTitle("Rear speakers")
            surroundEnabled?.let { v -> SwitchRow("Rear speakers on", "Switch off to hear only the soundbar", v, !working) { t -> surroundEnabled = t; change({ client.setEqValue(device, "SurroundEnable", if (t) 1 else 0) }, { surroundEnabled = v }) } }
            LevelSlider("TV level", tvLevel, -15..15, !working && surroundEnabled == true) { t, fail -> change({ client.setEqValue(device, "SurroundLevel", t) }, fail) { tvLevel = t } }
            LevelSlider("Music level", musicLevel, -15..15, !working && surroundEnabled == true) { t, fail -> change({ client.setEqValue(device, "MusicSurroundLevel", t) }, fail) { musicLevel = t } }
            surroundMode?.let { v -> SwitchRow("Full surround for music", if (v) "Rears play at full level" else "Rears add subtle ambience", v, !working) { t -> surroundMode = t; change({ client.setEqValue(device, "SurroundMode", if (t) 1 else 0) }, { surroundMode = v }) } }
        }
        NoteText(note)
    }
}

@Composable
internal fun DetailScreen(
    client: SonosController,
    system: SonosSystem,
    members: List<SonosZoneMember>,
    product: SonosProduct?,
    room: SonosDevice?,
    reconnect: (SonosDevice) -> Unit,
    openSound: () -> Unit,
    openGroup: () -> Unit,
    configureRoom: (SonosDevice) -> Unit,
    arrange: () -> Unit,
    renamed: () -> Unit,
    back: () -> Unit,
) {
    val device = product?.device ?: room
    var hardware by remember(room?.uid) { mutableStateOf<SonosDeviceSettings?>(null) }
    var note by remember(room?.uid) { mutableStateOf(Note.None) }
    var working by remember(room?.uid) { mutableStateOf(false) }
    var renameOpen by remember(room?.uid) { mutableStateOf(false) }
    var nameDraft by remember(room?.uid) { mutableStateOf(room?.roomName.orEmpty()) }
    var shownName by remember(room?.uid) { mutableStateOf(room?.roomName.orEmpty()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val devices = system.products.associate { it.device.uid to it.device } + system.rooms.associateBy { it.uid }
    val models = devices.mapValues { it.value.modelName }
    val setup = room?.let { r -> SonosSetup.roomSetups(members, devices).firstOrNull { it.main.uid == r.uid } }

    LaunchedEffect(room?.uid) {
        room?.let { r -> hardware = withContext(Dispatchers.IO) { runCatching { client.deviceSettings(r) }.getOrNull() } }
    }

    fun change(action: () -> Boolean, onFail: () -> Unit = {}, success: String, onSuccess: () -> Unit = {}) {
        if (working) return
        working = true; note = Note.None
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { action() } }.getOrDefault(false)
            note = if (ok) { onSuccess(); Note.ok(success) } else { onFail(); Note.fail(client.lastCommandError.ifBlank { "Setting wasn't changed" }) }
            working = false
        }
    }

    ScreenColumn {
        BackHeader(if (room != null) "Room settings" else "Speaker", back)
        if (device == null) { Text("Speaker unavailable", color = Muted); return@ScreenColumn }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductImage(device.modelName, Modifier.size(width = 112.dp, height = 72.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (room != null) shownName else device.modelName, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(setup?.summary(models) ?: device.modelName, color = Muted)
            }
        }
        if (product?.inferred == true) Text("Identified from the home theatre setup; this speaker doesn't report itself directly.", color = Muted, fontSize = 13.sp)

        if (room != null) {
            SettingCard {
                NavigationRow("Name", shownName, if (working) null else ({ nameDraft = shownName; renameOpen = true }))
                NavigationRow("Sound", "Tone, TV, Sub and surround", openSound)
                if (system.rooms.size > 1) NavigationRow("Play in other rooms", "Group this room with others", openGroup)
                if (SonosProtocol.supportsTvAudio(room.modelName)) NavigationRow("Reconnect TV sound", "Switch back to the TV input", if (working) null else ({ reconnect(room) }))
            }
            SettingCard {
                CardTitle("Speakers in this room")
                SetupRow(device.modelName, "Main speaker")
                setup?.stereoPartnerUid?.let { SetupRow(models[it] ?: device.modelName, "Right channel (stereo pair)") }
                setup?.surroundUids?.forEachIndexed { i, uid -> SetupRow(models[uid]?.takeIf(String::isNotBlank) ?: "Surround", if (i == 0) "Left rear" else "Right rear") }
                setup?.subUid?.let { SetupRow(models[it]?.takeIf(String::isNotBlank) ?: "Sonos Sub", "Sub") }
                SecondaryButton("Add or remove speakers", onClick = arrange)
            }
            if (hardware?.statusLight != null || hardware?.touchControls != null) SettingCard {
                CardTitle("On the speaker")
                hardware?.statusLight?.let { v ->
                    SwitchRow("Status light", "The small light on the front", v, !working) { t ->
                        val old = hardware; hardware = old?.copy(statusLight = t)
                        change({ client.setStatusLight(room, t) }, { hardware = old }, "Status light ${if (t) "on" else "off"}")
                    }
                }
                hardware?.touchControls?.let { v ->
                    SwitchRow("Touch controls", "Buttons on top of the speaker", v, !working) { t ->
                        val old = hardware; hardware = old?.copy(touchControls = t)
                        change({ client.setTouchControls(room, t) }, { hardware = old }, "Touch controls ${if (t) "on" else "off"}")
                    }
                }
            }
            SettingCard {
                CardTitle("Needs the Sonos app")
                Text("Trueplay tuning, software updates, voice assistants and TV remote setup.", color = Muted)
                NavigationRow("Open Sonos", if (sonosInstalled(context)) "Opens the Sonos app" else "Install the Sonos app", { openSonosApp(context) })
            }
        } else if (product != null) {
            val owner = system.rooms.firstOrNull { it.uid == product.device.uid || it.roomName.equals(product.device.roomName, ignoreCase = true) }
            if (owner != null) {
                Text("This speaker is set up as part of ${owner.roomName}.", color = Muted)
                NavigationRow("Open ${owner.roomName}", "Room settings", onClick = { configureRoom(owner) })
            }
        }
        NoteText(note)
    }

    if (renameOpen && room != null) AlertDialog(
        onDismissRequest = { if (!working) renameOpen = false },
        title = { Text("Rename room") },
        text = { OutlinedTextField(nameDraft, { nameDraft = it.take(40) }, singleLine = true, label = { Text("Room name") }) },
        confirmButton = {
            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {
                val requested = nameDraft.trim()
                renameOpen = false
                change({ client.renameRoom(room, requested) }, success = "Renamed to $requested") { shownName = requested; renamed() }
            }, enabled = nameDraft.isNotBlank() && !working) { Text("Save") }
        },
        dismissButton = { TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { renameOpen = false }, enabled = !working) { Text("Cancel") } },
    )
}

@Composable
private fun SetupRow(model: String, role: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        ProductImage(model, Modifier.size(width = 72.dp, height = 50.dp))
        Spacer(Modifier.width(12.dp))
        Column { Text(model, fontWeight = FontWeight.Medium); Text(role, color = Muted, fontSize = 13.sp) }
    }
}

@Composable
internal fun SettingsScreen(message: String, system: SonosSystem, members: List<SonosZoneMember>, discovering: Boolean, refresh: () -> Unit, addSpeakers: () -> Unit, configure: (SonosDevice) -> Unit) {
    val context = LocalContext.current
    val devices = system.products.associate { it.device.uid to it.device } + system.rooms.associateBy { it.uid }
    val setups = SonosSetup.roomSetups(members, devices).associateBy { it.main.uid }
    val models = devices.mapValues { it.value.modelName }
    ScreenColumn {
        Text("Settings", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        SettingCard {
            NavigationRow("Add or arrange speakers", "New speaker, Sub, surrounds, stereo", addSpeakers) { PlusGlyph(22.dp, Ink) }
        }
        SectionLabel("Rooms")
        SettingCard {
            if (system.rooms.isEmpty()) Text("No rooms found yet.", color = Muted)
            system.rooms.forEach { room ->
                NavigationRow(room.roomName.ifBlank { "Unnamed room" }, setups[room.uid]?.summary(models) ?: room.modelName, { configure(room) }) {
                    ProductImage(room.modelName, Modifier.size(40.dp))
                }
            }
        }
        SectionLabel("Connection")
        SettingCard {
            Text(message, color = Muted)
            SecondaryButton(if (discovering) "Searching…" else "Search for speakers again", enabled = !discovering, onClick = refresh)
        }
        SectionLabel("Sonos app")
        SettingCard {
            Text("Baton handles everyday control and speaker arrangement. The Sonos app is still needed for first-time setup of a brand-new speaker, software updates, Trueplay and music service sign-in.", color = Muted)
            NavigationRow("Open Sonos", if (sonosInstalled(context)) "Opens the Sonos app" else "Install the Sonos app", { openSonosApp(context) })
        }
        Text("Baton ${BuildConfigCompat.versionName(context)}", color = Muted, fontSize = 12.sp)
    }
}

internal object BuildConfigCompat {
    fun versionName(context: android.content.Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}
