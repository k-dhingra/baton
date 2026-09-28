package app.rooms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which guided flow is open. Each is a short linear wizard: choose → review → working → result. */
private enum class Flow { Hub, NewSpeaker, Sub, Surrounds, Stereo }

private sealed class Stage {
    data object Choose : Stage()
    data class Review(val task: SetupTask) : Stage()
    data class Working(val task: SetupTask, val step: String) : Stage()
    data class Result(val task: SetupTask, val outcome: SetupOutcome) : Stage()
}

@Composable
internal fun AddSpeakersScreen(
    client: SonosController,
    system: SonosSystem,
    members: List<SonosZoneMember>,
    discovering: Boolean,
    refresh: () -> Unit,
    changed: () -> Unit,
    back: () -> Unit,
) {
    var flow by remember { mutableStateOf(Flow.Hub) }
    var stage by remember { mutableStateOf<Stage>(Stage.Choose) }
    var liveMembers by remember { mutableStateOf(members) }
    var preselect by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(members) { if (members.isNotEmpty()) liveMembers = members }
    val scope = rememberCoroutineScope()

    val devices = system.products.associate { it.device.uid to it.device } + system.rooms.associateBy { it.uid }
    val models = devices.mapValues { it.value.modelName }
    val setups = SonosSetup.roomSetups(liveMembers, devices)

    fun close() {
        when {
            stage is Stage.Working -> Unit
            stage !is Stage.Choose && stage !is Stage.Result -> stage = Stage.Choose
            flow != Flow.Hub -> { flow = Flow.Hub; stage = Stage.Choose; preselect = null }
            else -> back()
        }
    }
    androidx.activity.compose.BackHandler(enabled = true) { close() }

    fun run(task: SetupTask) {
        stage = Stage.Working(task, "Starting…")
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { client.runSetup(task, devices) { step -> scope.launch { if (stage is Stage.Working) stage = Stage.Working(task, step) } } }
                    .getOrElse { SetupOutcome.Failed("Couldn't finish the speaker change. Some grouping may have changed; refresh Rooms before trying again.") }
            }
            stage = Stage.Result(task, outcome)
            system.rooms.firstOrNull()?.let { any -> withContext(Dispatchers.IO) { client.zoneMembers(any) } }?.let { liveMembers = it }
            changed()
        }
    }

    ScreenColumn {
        val title = when (flow) {
            Flow.Hub -> "Add & arrange"
            Flow.NewSpeaker -> "New speaker"
            Flow.Sub -> "Add a Sub"
            Flow.Surrounds -> "Add rear surrounds"
            Flow.Stereo -> "Make a stereo pair"
        }
        BackHeader(title, if (stage is Stage.Working) null else ({ close() }))

        when (val s = stage) {
            is Stage.Working -> WorkingPanel(s.step)
            is Stage.Result -> ResultPanel(s.outcome,
                done = { flow = Flow.Hub; stage = Stage.Choose; preselect = null },
                retry = { stage = Stage.Review(s.task) })
            is Stage.Review -> ReviewPanel(s.task, SonosSetup.preview(s.task, liveMembers), confirm = { run(s.task) }, cancel = { stage = Stage.Choose })
            Stage.Choose -> when (flow) {
                Flow.Hub -> Hub(setups, models, discovering, refresh,
                    open = { flow = it; stage = Stage.Choose },
                    remove = { stage = Stage.Review(it) })
                Flow.NewSpeaker -> NewSpeakerFlow(client, system, setups) { kind, uid ->
                    preselect = uid
                    changed()
                    flow = when (kind) { SpeakerKind.SUB -> Flow.Sub; else -> Flow.Stereo }
                }
                Flow.Sub -> SubChooser(client, setups, preselect) { stage = Stage.Review(it) }
                Flow.Surrounds -> SurroundChooser(client, setups, preselect) { stage = Stage.Review(it) }
                Flow.Stereo -> StereoChooser(client, setups, preselect, surrounds = { flow = Flow.Surrounds }) { stage = Stage.Review(it) }
            }
        }
    }
}

// ---- Hub ------------------------------------------------------------------------------------

@Composable
private fun Hub(setups: List<RoomSetup>, models: Map<String, String>, discovering: Boolean, refresh: () -> Unit, open: (Flow) -> Unit, remove: (SetupTask) -> Unit) {
    Text("Pick what you want to do. Baton checks what's possible with the speakers you have.", color = Muted)

    SectionLabel("Add")
    TaskCard("New Sonos speaker", "Just unboxed? Start here.", null, primary = true) { open(Flow.NewSpeaker) }
    TaskCard("Add a Sub", "Deeper bass for a room", SonosSetup.addSubBlocker(setups)) { open(Flow.Sub) }
    TaskCard("Add rear surrounds", "Two speakers behind you for a soundbar", SonosSetup.surroundBlocker(setups)) { open(Flow.Surrounds) }
    TaskCard("Make a stereo pair", "Two matching speakers as left and right", SonosSetup.stereoBlocker(setups)) { open(Flow.Stereo) }

    val removals = SonosSetup.separateOptions(setups, models)
    if (removals.isNotEmpty()) {
        SectionLabel("Separate")
        SettingCard {
            removals.forEach { task ->
                val (title, room) = when (task) {
                    is SetupTask.RemoveSatellite -> "Remove ${task.satelliteLabel}".replace("Remove Sonos ", "Remove ") to task.main.roomName
                    is SetupTask.SeparatePair -> "Separate stereo pair" to task.main.roomName
                    else -> task.title to ""
                }
                NavigationRow(title, "From $room", { remove(task) })
            }
        }
    }

    SectionLabel("Your speakers")
    SettingCard {
        setups.forEach { NavigationRow(it.main.roomName.ifBlank { it.main.modelName }, it.summary(models), null) { ProductImage(it.main.modelName, Modifier.size(40.dp)) } }
        if (setups.isEmpty()) Text("Reading your setup…", color = Muted)
        TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = refresh, enabled = !discovering) { Text(if (discovering) "Searching…" else "Search for speakers again") }
    }
}

@Composable
private fun TaskCard(title: String, subtitle: String, blocker: String?, primary: Boolean = false, onClick: () -> Unit) {
    val enabled = blocker == null
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).semantics {
            role = Role.Button; contentDescription = if (enabled) "$title. $subtitle" else "$title. Not available: $blocker"
        },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (primary) Ink else Color.White),
        border = BorderStroke(1.dp, if (primary) Ink else Rule),
    ) {
        Row(Modifier.padding(16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = when { primary -> Color.White; enabled -> Ink; else -> Muted })
                Text(blocker ?: subtitle, fontSize = 13.sp, color = if (primary) Color(0xCCFFFFFF) else Muted)
            }
            if (enabled) ChevronRight(18.dp, if (primary) Color.White else Muted)
        }
    }
}

// ---- New speaker ----------------------------------------------------------------------------

/**
 * Honest split: Sonos requires its own app (Bluetooth + account) to put a brand-new speaker on Wi-Fi.
 * Baton keeps that detour short, then watches the network and takes over as soon as the speaker appears.
 */
@Composable
private fun NewSpeakerFlow(client: SonosController, system: SonosSystem, setups: List<RoomSetup>, next: (SpeakerKind, String) -> Unit) {
    val context = LocalContext.current
    val baseline = remember { (system.products.map { it.device.uid } + system.rooms.map { it.uid }).toSet() }
    var openedSonos by remember { mutableStateOf(false) }
    var handoffError by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<SonosDevice>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(openedSonos) {
        if (!openedSonos) return@LaunchedEffect
        searching = true
        var pause = 4_000L
        while (found.isEmpty()) {
            val fresh = withContext(Dispatchers.IO) { runCatching { client.discoverSystem() }.getOrNull() }
            if (fresh != null) {
                val all = (fresh.products.map { it.device } + fresh.rooms).distinctBy { it.uid }
                found = all.filter { it.uid !in baseline && it.ip.isNotBlank() }
            }
            if (found.isEmpty()) { delay(pause); pause = (pause * 2).coerceAtMost(20_000L) }
        }
        searching = false
    }

    Step(1, "Plug it in", "Place the speaker where it will live and connect power. Wait for the light to flash green.", done = openedSonos)
    Step(2, "Connect it to Wi-Fi in the Sonos app",
        "Sonos only lets its own app do this part: it uses Bluetooth and your Sonos account. Tap Add product in Sonos, follow it until the speaker is added, then come back here.",
        done = found.isNotEmpty()) {
        if (!openedSonos) PrimaryButton(if (sonosInstalled(context)) "Open Sonos" else "Install Sonos") {
            val launched = openSonosApp(context)
            handoffError = !launched
            if (launched) openedSonos = true
        }
        else TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { handoffError = !openSonosApp(context) }) { Text("Open Sonos again") }
        if (handoffError) Text("Couldn't open Sonos or the app store. Open Sonos manually, then come back.", color = Danger, fontSize = 13.sp)
        if (!openedSonos) SecondaryButton("Already set up? Find it now") { openedSonos = true }
    }
    var waited by remember { mutableIntStateOf(0) }
    LaunchedEffect(searching) { while (searching) { delay(1_000); waited++ } }
    Step(3, "Baton finds it", if (found.isEmpty()) "Baton watches your network and finds the speaker automatically." else "Found it.", done = found.isNotEmpty()) {
        if (searching && found.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
            Text("Looking for new speakers…", color = Muted, fontSize = 13.sp)
        }
        if (searching && found.isEmpty() && waited >= 20) Text(
            "Already finished in Sonos before opening this? Then it's already in Rooms. Go back and pick Add a Sub, rear surrounds or stereo pair.",
            color = Muted, fontSize = 13.sp,
        )
    }

    if (found.isNotEmpty()) {
        SectionLabel("What should it do?")
        found.forEach { speaker ->
            val kind = speakerKind(speaker.modelName)
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProductImage(speaker.modelName, Modifier.size(width = 80.dp, height = 56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column { Text(speaker.modelName, fontWeight = FontWeight.SemiBold); Text("Named “${speaker.roomName}”", color = Muted, fontSize = 13.sp) }
                }
                when (kind) {
                    SpeakerKind.SUB -> {
                        val targets = SonosSetup.subTargets(setups)
                        if (targets.isEmpty()) Text("Every compatible room already has a Sub.", color = Muted)
                        else PrimaryButton("Use it as a Sub") { next(SpeakerKind.SUB, speaker.uid) }
                    }
                    SpeakerKind.SPEAKER -> {
                        Text("Keep it as its own room, or pair it with a matching speaker.", color = Muted, fontSize = 13.sp)
                        PrimaryButton("Pair or use as surrounds") { next(SpeakerKind.SPEAKER, speaker.uid) }
                    }
                    else -> Text("It's ready to use as its own room.", color = Muted)
                }
                Text("Nothing else to do if you want it as its own room. It's already in Rooms.", color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, body: String, done: Boolean, content: @Composable ColumnScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        StepBadge(number, done)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(body, color = Muted)
            content()
        }
    }
}

// ---- Choosers -------------------------------------------------------------------------------

@Composable
private fun IdentifyButton(client: SonosController, device: SonosDevice?) {
    var blinking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    SmallAction(if (blinking) "Blinking…" else "Blink light", enabled = device != null && !blinking) {
        val d = device ?: return@SmallAction
        blinking = true
        scope.launch { withContext(Dispatchers.IO) { runCatching { client.identify(d) } }; blinking = false }
    }
}

@Composable
private fun SubChooser(client: SonosController, setups: List<RoomSetup>, preselect: String?, review: (SetupTask) -> Unit) {
    val subs = SonosSetup.spare(setups, SpeakerKind.SUB)
    val rooms = SonosSetup.subTargets(setups)
    var sub by remember { mutableStateOf(subs.firstOrNull { it.uid == preselect } ?: subs.singleOrNull()) }
    var room by remember { mutableStateOf(rooms.singleOrNull() ?: rooms.firstOrNull { speakerKind(it.modelName) == SpeakerKind.SOUNDBAR }) }
    if (subs.isEmpty() || rooms.isEmpty()) { Text(SonosSetup.addSubBlocker(setups) ?: "Nothing to set up.", color = Muted); return }

    SectionLabel("1. Which Sub?")
    SettingCard {
        subs.forEach { s -> ChoiceRow(s.roomName, s.modelName, sub?.uid == s.uid, image = s.modelName) { sub = s } }
        if (subs.size > 1) IdentifyButton(client, sub)
    }
    SectionLabel("2. Which room gets the bass?")
    SettingCard { rooms.forEach { r -> ChoiceRow(r.roomName, r.modelName, room?.uid == r.uid, image = r.modelName) { room = r } } }
    PrimaryButton("Review", enabled = sub != null && room != null) { review(SetupTask.AddSub(room!!, sub!!)) }
}

@Composable
private fun SurroundChooser(client: SonosController, setups: List<RoomSetup>, preselect: String?, review: (SetupTask) -> Unit) {
    val bars = SonosSetup.surroundTargets(setups)
    val candidates = SonosSetup.pairableGroups(setups).values.flatten()
    var bar by remember { mutableStateOf(bars.singleOrNull()) }
    var left by remember { mutableStateOf(candidates.firstOrNull { it.uid == preselect }) }
    var right by remember { mutableStateOf<SonosDevice?>(null) }
    if (bars.isEmpty() || candidates.size < 2) { Text(SonosSetup.surroundBlocker(setups) ?: "Nothing to set up.", color = Muted); return }

    SectionLabel("1. Which soundbar?")
    SettingCard { bars.forEach { b -> ChoiceRow(b.roomName, b.modelName, bar?.uid == b.uid, image = b.modelName) { bar = b } } }
    SectionLabel("2. Pick the two rear speakers")
    Text("Choose the one on your left first (when you're sitting facing the TV), then the right.", color = Muted, fontSize = 13.sp)
    SettingCard {
        candidates.forEach { c ->
            val label = when (c.uid) { left?.uid -> "Left rear"; right?.uid -> "Right rear"; else -> c.modelName }
            val matches = left == null || sameModel(left!!, c)
            ChoiceRow(c.roomName, if (matches) label else "Must match ${left?.modelName}", c.uid == left?.uid || c.uid == right?.uid, enabled = matches, multi = true, image = c.modelName) {
                when (c.uid) {
                    left?.uid -> { left = right; right = null }
                    right?.uid -> right = null
                    else -> if (left == null) left = c else right = c
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IdentifyButton(client, right ?: left)
            if (left != null && right != null) SmallAction("Swap left and right") { val l = left; left = right; right = l }
        }
    }
    PrimaryButton("Review", enabled = bar != null && left != null && right != null) { review(SetupTask.AddSurrounds(bar!!, left!!, right!!)) }
}

@Composable
private fun StereoChooser(client: SonosController, setups: List<RoomSetup>, preselect: String?, surrounds: () -> Unit, review: (SetupTask) -> Unit) {
    val candidates = SonosSetup.pairableGroups(setups).values.flatten()
    var left by remember { mutableStateOf(candidates.firstOrNull { it.uid == preselect }) }
    var right by remember { mutableStateOf<SonosDevice?>(null) }
    var name by remember { mutableStateOf(left?.roomName.orEmpty()) }
    LaunchedEffect(left?.uid) { if (name.isBlank()) name = left?.roomName.orEmpty() }
    if (candidates.size < 2) {
        Text(SonosSetup.stereoBlocker(setups) ?: "Nothing to pair.", color = Muted)
        if (preselect != null) Text("Once you have a second matching speaker, come back here to pair them.", color = Muted)
        return
    }
    if (SonosSetup.surroundBlocker(setups) == null) SettingCard {
        NavigationRow("Going behind a soundbar?", "Set them up as rear surrounds instead", surrounds)
    }

    SectionLabel("1. Pick two matching speakers")
    Text("Tap the left speaker first (as you face them), then the right.", color = Muted, fontSize = 13.sp)
    SettingCard {
        candidates.forEach { c ->
            val label = when (c.uid) { left?.uid -> "Left"; right?.uid -> "Right"; else -> c.modelName }
            val matches = left == null || sameModel(left!!, c)
            ChoiceRow(c.roomName, if (matches) label else "Must match ${left?.modelName}", c.uid == left?.uid || c.uid == right?.uid, enabled = matches, multi = true, image = c.modelName) {
                when (c.uid) {
                    left?.uid -> { left = right; right = null }
                    right?.uid -> right = null
                    else -> if (left == null) left = c else right = c
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IdentifyButton(client, right ?: left)
            if (left != null && right != null) SmallAction("Swap left and right") { val l = left; left = right; right = l }
        }
    }
    SectionLabel("2. Name the room")
    OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Room name") }, modifier = Modifier.fillMaxWidth())
    PrimaryButton("Review", enabled = left != null && right != null && name.isNotBlank()) { review(SetupTask.StereoPair(left!!, right!!, name.trim())) }
}

// ---- Review / working / result --------------------------------------------------------------

@Composable
private fun ReviewPanel(task: SetupTask, lines: List<String>, confirm: () -> Unit, cancel: () -> Unit) {
    SettingCard {
        CardTitle("What will happen")
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.Top) {
                Text("•", modifier = Modifier.width(16.dp), color = Muted)
                Text(line)
            }
        }
    }
    val destructive = task is SetupTask.RemoveSatellite || task is SetupTask.SeparatePair
    Button(
        onClick = confirm,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = if (destructive) ButtonDefaults.buttonColors(containerColor = Danger) else ButtonDefaults.buttonColors(),
    ) { Text(task.confirmLabel, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    SecondaryButton("Cancel", onClick = cancel)
}

@Composable
private fun WorkingPanel(step: String) {
    SettingCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Ink)
            Column { Text(step, fontWeight = FontWeight.Medium); Text("Keep Baton open. This usually takes under 30 seconds.", color = Muted, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun ResultPanel(outcome: SetupOutcome, done: () -> Unit, retry: () -> Unit) {
    val (title, body, ok) = when (outcome) {
        is SetupOutcome.Done -> Triple("Done", outcome.message, true)
        is SetupOutcome.Pending -> Triple("Almost there", outcome.message, false)
        is SetupOutcome.Failed -> Triple("That didn't work", outcome.message, false)
    }
    SettingCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ok) Surface(shape = RoundedCornerShape(16.dp), color = Good, modifier = Modifier.size(32.dp)) { Box(contentAlignment = Alignment.Center) { CheckGlyph(18.dp, Color.White) } }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = if (outcome is SetupOutcome.Failed) Danger else Ink)
        }
        Text(body)
    }
    PrimaryButton("Done", onClick = done)
    if (outcome is SetupOutcome.Failed) SecondaryButton("Try again", onClick = retry)
}
