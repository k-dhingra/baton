package app.rooms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import app.rooms.sonos.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SonosWhite = Color(0xFFFAFAFA)
private val SonosBlack = Color(0xFF101010)
private val SonosGrey = Color(0xFF6C6C68)
private val SonosRule = Color(0xFFE4E4E1)
private val SonosError = Color(0xFFB3261E)
private enum class Page { System, Now, Queue, Favourites, Group, Sound, Detail, Settings }

class MainActivity : ComponentActivity() {
    private val permission: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        if (Build.VERSION.SDK_INT >= 37 && !hasLocalAccess()) permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun hasLocalAccess() = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun render() = setContent {
        SonosTheme {
            if (hasLocalAccess()) RoomsApp(SonosClient(applicationContext), applicationContext) else PermissionScreen {
                permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
        }
    }
}

@Composable
private fun PermissionScreen(allow: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = SonosWhite) {
        Column(
            Modifier.statusBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Wordmark()
            Text("Local network access", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("Baton needs local network access to find and control your Sonos speakers.", color = SonosGrey)
            Button(onClick = allow) { Text("Allow access") }
        }
    }
}

@Composable
fun RoomsApp(client: SonosClient, appContext: Context) {
    var system by remember { mutableStateOf(SonosSystem(emptyList(), emptyList())) }
    var selected by remember { mutableStateOf<SonosDevice?>(null) }
    var selectedState by remember { mutableStateOf<SonosRoomState?>(null) }
    var selectedStale by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(Page.System) }
    var message by remember { mutableStateOf("Finding your system…") }
    var discovering by remember { mutableStateOf(false) }
    var miniBusy by remember { mutableStateOf(false) }
    var detailProduct by remember { mutableStateOf<SonosProduct?>(null) }
    var detailRoom by remember { mutableStateOf<SonosDevice?>(null) }
    var detailBackPage by remember { mutableStateOf(Page.System) }
    var soundBackPage by remember { mutableStateOf(Page.Now) }
    var groupBackPage by remember { mutableStateOf(Page.Now) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    val quickPrefs = remember { appContext.getSharedPreferences("quick_connect", Context.MODE_PRIVATE) }
    var quickTargetUid by remember { mutableStateOf(quickPrefs.getString("uid", null)) }
    var quickBusy by remember { mutableStateOf(false) }
    var quickStatus by remember { mutableStateOf("") }
    var reconnectFeedback by remember { mutableStateOf<QuickConnectFeedback?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (discovering) return
        discovering = true
        val generation = ++refreshGeneration
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { client.discoverSystem() } }
            if (generation == refreshGeneration) {
                result.onSuccess { fresh ->
                    system = fresh
                    selected = selected?.let { old -> fresh.rooms.firstOrNull { it.uid == old.uid } }
                        ?: fresh.rooms.firstOrNull()
                    message = client.lastDiscoveryMessage
                }.onFailure { message = "Discovery failed · retry" }
                discovering = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(selected?.uid) {
        val device = selected ?: return@LaunchedEffect
        val uid = device.uid
        while (true) {
            runCatching { withContext(Dispatchers.IO) { client.getRoomState(device) } }
                .onSuccess { if (selected?.uid == uid) { selectedState = it; selectedStale = false } }
                .onFailure { if (selected?.uid == uid) selectedStale = true }
            delay(3_000)
        }
    }

    BackHandler(enabled = page != Page.System) {
        page = when (page) {
            Page.Queue, Page.Favourites -> Page.Now
            Page.Group -> groupBackPage
            Page.Sound -> soundBackPage
            Page.Detail -> detailBackPage
            Page.Now, Page.Settings -> Page.System
            Page.System -> Page.System
        }
    }

    if (system.rooms.isEmpty() && (discovering || message == "Finding your system…")) {
        DiscoveryScreen()
        return
    }

    fun miniPlayPause() {
        val device = selected ?: return
        if (miniBusy) return
        miniBusy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (selectedState?.transport == "PLAYING") client.pause(device) else client.play(device)
            }
            if (ok) {
                runCatching { withContext(Dispatchers.IO) { client.getRoomState(device) } }
                    .onSuccess { if (selected?.uid == device.uid) selectedState = it }
            } else message = client.lastCommandError.ifBlank { "Playback command failed" }
            miniBusy = false
        }
    }

    fun reconnect(target: SonosDevice) {
        if (quickBusy) return
        quickBusy = true
        quickStatus = ""
        scope.launch {
            try {
                quickConnectWithFeedback(target, { feedback ->
                    reconnectFeedback = feedback
                    if (!feedback.running) quickStatus = "${feedback.title} · ${feedback.message}"
                }) { withContext(Dispatchers.IO) { client.quickConnect(target) } }
            } finally {
                quickBusy = false
            }
        }
    }

    fun quickConnect() {
        val target = quickTargetUid?.let { uid -> system.rooms.firstOrNull { it.uid == uid } } ?: return
        reconnect(target)
    }

    Scaffold(
        containerColor = SonosWhite,
        bottomBar = {
            Column {
                if (selected != null && page != Page.Now) {
                    MiniPlayer(selected!!, selectedState, selectedStale, miniBusy, { page = Page.Now }, ::miniPlayPause)
                }
                NavigationBar(containerColor = Color.White) {
                    listOf(Page.System to "System", Page.Now to "Player", Page.Settings to "Settings").forEach { (destination, label) ->
                        NavigationBarItem(
                            selected = page == destination,
                            onClick = { page = destination },
                            icon = {
                                when (destination) {
                                    Page.System -> Text("⌂")
                                    Page.Now -> PlaybackGlyph(false, 18.dp, LocalContentColor.current)
                                    else -> Text("⋯")
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (page) {
                Page.System -> SystemScreen(client, system, message, discovering, ::refresh, quickTargetUid, quickBusy, quickStatus,
                    selectQuickTarget = { target ->
                        quickPrefs.edit {
                            putString("uid", target.uid)
                            putString("name", target.roomName)
                        }
                        quickTargetUid = target.uid
                        quickStatus = ""
                    }, quickConnect = ::quickConnect,
                    openRoom = { selected = it; page = Page.Now },
                    roomDetail = { room ->
                        selected = room
                        detailRoom = room
                        detailProduct = system.products.firstOrNull { it.device.uid == room.uid }
                        detailBackPage = Page.System
                        page = Page.Detail
                    },
                    productDetail = { product ->
                        detailProduct = product
                        detailRoom = null
                        detailBackPage = Page.System
                        page = Page.Detail
                    },
                )
                Page.Now -> NowPlayingScreen(client, system, selected, selectedState, selectedStale,
                    choose = { selected = it }, update = { selectedState = it; selectedStale = false },
                    queue = { page = Page.Queue }, favourites = { page = Page.Favourites },
                    group = { groupBackPage = Page.Now; page = Page.Group }, sound = { soundBackPage = Page.Now; page = Page.Sound },
                )
                Page.Queue -> ItemsScreen("Queue", selected, { client.queue(it) }, client) { page = Page.Now }
                Page.Favourites -> ItemsScreen("Sonos Favourites", selected, { client.favourites(it) }, null) { page = Page.Now }
                Page.Group -> GroupScreen(client, system, selected) { page = groupBackPage }
                Page.Sound -> SoundScreen(client, selected) { page = soundBackPage }
                Page.Detail -> DetailScreen(client, system, detailProduct, detailRoom,
                    reconnect = ::reconnect,
                    openSound = { soundBackPage = Page.Detail; page = Page.Sound },
                    openGroup = { groupBackPage = Page.Detail; page = Page.Group },
                    configureRoom = { target ->
                        selected = target
                        detailRoom = target
                        detailProduct = system.products.firstOrNull { it.device.uid == target.uid }
                    },
                    back = { page = detailBackPage })
                Page.Settings -> SettingsScreen(message, system, ::refresh) { room ->
                    selected = room
                    detailRoom = room
                    detailProduct = system.products.firstOrNull { it.device.uid == room.uid }
                    detailBackPage = Page.Settings
                    page = Page.Detail
                }
            }
        }
    }
    reconnectFeedback?.let { feedback ->
        AlertDialog(
            onDismissRequest = { if (!feedback.running) reconnectFeedback = null },
            title = { Text(feedback.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(feedback.roomName, fontWeight = FontWeight.Medium)
                    if (feedback.running) CircularProgressIndicator(Modifier.size(32.dp))
                    Text(feedback.message)
                }
            },
            confirmButton = {
                TextButton(onClick = { reconnectFeedback = null }, enabled = !feedback.running, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (feedback.running) "Working…" else "Done")
                }
            },
        )
    }
}

@Composable
private fun DiscoveryScreen() {
    var dots by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(450)
            dots = dots % 3 + 1
        }
    }
    Surface(Modifier.fillMaxSize(), color = SonosWhite) {
        Column(
            Modifier.statusBarsPadding().fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Finding your devices", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                Text(".".repeat(dots), fontSize = 28.sp)
            }
        }
    }
}

@Composable
private fun SystemScreen(
    client: SonosClient,
    system: SonosSystem,
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
    productDetail: (SonosProduct) -> Unit,
) {
    var summaries by remember { mutableStateOf<Map<String, SonosRoomState>>(emptyMap()) }
    var productsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(system.rooms.map { it.uid }) {
        while (true) {
            system.rooms.forEach { room ->
                runCatching { withContext(Dispatchers.IO) { client.getRoomState(room) } }
                    .onSuccess { summaries = summaries + (room.uid to it) }
            }
            delay(12_000)
        }
    }
    Column(
        Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wordmark(Modifier.weight(1f))
            TextButton(onClick = refresh, enabled = !discovering, modifier = Modifier.semantics { contentDescription = "Refresh Sonos system" }) {
                Text(if (discovering) "…" else "↻", fontSize = 24.sp)
            }
        }
        Text("Your System", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = SonosGrey, fontSize = 13.sp)
        QuickConnectCard(system.rooms, quickTargetUid, quickBusy, quickStatus, selectQuickTarget, quickConnect)
        if (system.rooms.isEmpty() && !discovering) Text("No rooms found. Check this phone is on the same Wi-Fi.", color = SonosError)
        system.rooms.forEach { room ->
            RoomCard(room, summaries[room.uid], { openRoom(room) }, { roomDetail(room) })
        }
        HorizontalDivider(color = SonosRule)
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { productsOpen = !productsOpen }
                .semantics {
                    role = Role.Button
                    contentDescription = if (productsOpen) "Hide products" else "Show products"
                    stateDescription = if (productsOpen) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Products", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(if (productsOpen) "${system.products.size} devices" else "${system.products.size} devices · hidden", color = SonosGrey, fontSize = 13.sp)
            }
            Text(if (productsOpen) "Hide products" else "Show products", color = SonosBlack, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            ChevronGlyph(expanded = productsOpen, size = 20.dp, color = SonosBlack)
        }
        if (productsOpen) system.products.forEach { product -> ProductRow(product) { productDetail(product) } }
    }
}

@Composable
private fun QuickConnectCard(
    rooms: List<SonosDevice>, targetUid: String?, busy: Boolean, status: String,
    selectTarget: (SonosDevice) -> Unit, connect: () -> Unit,
) {
    var chooserOpen by remember { mutableStateOf(false) }
    val target = targetUid?.let { uid -> rooms.firstOrNull { it.uid == uid } }
    val supportedRooms = rooms.filter { SonosProtocol.supportsTvAudio(it.modelName) }
    SettingCard {
        Text("Quick connect", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Select a device, then restore its TV audio input.", color = SonosGrey)
        OutlinedButton(
            onClick = { chooserOpen = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            border = BorderStroke(1.dp, if (target == null) SonosBlack else SonosRule),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Device", color = SonosGrey, fontSize = 12.sp)
                Text(
                    target?.let { "${it.roomName.ifBlank { "Unnamed room" }} · ${it.modelName}" }
                        ?: if (targetUid != null) "Selected device unavailable" else "Choose device",
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ChevronGlyph(expanded = false, size = 20.dp, color = SonosBlack)
        }
        Button(
            onClick = connect,
            enabled = target != null && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = "Quick connect TV audio" },
        ) { Text(if (busy) "Selecting…" else "Quick connect") }
        if (supportedRooms.isEmpty()) Text("No devices with a TV audio input found.", color = SonosGrey)
        if (status.isNotBlank()) Text(status, color = if (status.contains("failed", true) || status.contains("unavailable", true) || status.contains("Un-group", true)) SonosError else SonosGrey)
    }
    if (chooserOpen) AlertDialog(
        onDismissRequest = { chooserOpen = false }, title = { Text("Choose device") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            supportedRooms.forEach { room ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { selectTarget(room); chooserOpen = false },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(room.roomName.ifBlank { "Unnamed room" }, fontWeight = FontWeight.Medium)
                        Text(room.modelName, color = SonosGrey, fontSize = 13.sp)
                    }
                    RadioButton(selected = room.uid == targetUid, onClick = { selectTarget(room); chooserOpen = false })
                }
            }
            if (supportedRooms.isEmpty()) Text("No devices with a TV audio input found.", color = SonosGrey)
        } },
        confirmButton = { TextButton(onClick = { chooserOpen = false }) { Text("Cancel") } },
    )
}

@Composable
private fun RoomCard(room: SonosDevice, state: SonosRoomState?, open: () -> Unit, details: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = open),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, SonosRule),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(room.modelName, Modifier.size(width = 104.dp, height = 78.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(room.roomName.ifBlank { "Unnamed room" }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(state?.title?.takeIf { it.isNotBlank() } ?: SonosProtocol.sourceLabel(state?.source.orEmpty()), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${room.modelName} · ${transportLabel(state?.transport)} · ${state?.volume?.let { "$it%" } ?: "—"}", color = SonosGrey, fontSize = 12.sp)
            }
            TextButton(onClick = details, modifier = Modifier.heightIn(min = 48.dp)) { Text("Settings") }
        }
    }
}

@Composable
private fun ProductRow(product: SonosProduct, open: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 84.dp).clickable(onClick = open).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProductImage(product.device.modelName, Modifier.size(width = 100.dp, height = 72.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(product.device.modelName, fontWeight = FontWeight.SemiBold)
            Text(if (product.inferred) "Identified from theatre setup" else product.device.roomName, color = SonosGrey, fontSize = 13.sp)
        }
        Text("›", fontSize = 24.sp, color = SonosGrey)
    }
}

@Composable
private fun MiniPlayer(
    room: SonosDevice,
    state: SonosRoomState?,
    stale: Boolean,
    busy: Boolean,
    open: () -> Unit,
    playPause: () -> Unit,
) {
    Surface(color = Color.White, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(onClick = open).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProductImage(room.modelName, Modifier.size(54.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(state?.title?.takeIf { it.isNotBlank() } ?: SonosProtocol.sourceLabel(state?.source.orEmpty()), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${room.roomName}${if (stale) " · offline" else ""}", color = if (stale) SonosError else SonosGrey, fontSize = 12.sp)
            }
            PlaybackButton(
                playing = state?.transport == "PLAYING",
                onClick = playPause,
                enabled = !busy && !SonosProtocol.isTvSource(state?.source.orEmpty()),
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

@Composable
private fun NowPlayingScreen(
    client: SonosClient,
    system: SonosSystem,
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
    var mute by remember(room?.uid) { mutableStateOf(state?.mute ?: false) }
    var actions by remember(room?.uid) { mutableStateOf<Set<String>>(emptySet()) }
    var roomMenuOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state?.volume, state?.mute) {
        state?.volume?.let { committedVolume = it; if (!working) volume = it.toFloat() }
        state?.mute?.let { if (!working) mute = it }
    }
    LaunchedEffect(room?.uid, state?.transport, state?.source) {
        room?.let { actions = withContext(Dispatchers.IO) { client.currentTransportActions(it) } }
    }

    fun command(action: () -> Boolean, rollback: (() -> Unit)? = null) {
        val device = room ?: return
        if (working) return
        working = true
        error = ""
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { action() } }.getOrDefault(false)
            if (ok) {
                runCatching { withContext(Dispatchers.IO) { client.getRoomState(device) } }
                    .onSuccess(update).onFailure { error = "Command worked, but readback failed" }
            } else {
                rollback?.invoke()
                error = client.lastCommandError.ifBlank { "Sonos rejected the command" }
            }
            working = false
        }
    }

    Column(
        Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Now Playing", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
        if (system.rooms.size > 1) {
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { roomMenuOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("ROOM", color = SonosGrey, fontSize = 11.sp, letterSpacing = 1.sp)
                        Text(room?.roomName.orEmpty(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("⌄", color = SonosGrey, fontSize = 22.sp)
                }
                DropdownMenu(expanded = roomMenuOpen, onDismissRequest = { roomMenuOpen = false }) {
                    system.rooms.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.roomName) },
                            onClick = { choose(candidate); roomMenuOpen = false },
                            trailingIcon = { if (candidate.uid == room?.uid) Text("✓") },
                        )
                    }
                }
            }
        }
        if (room == null) {
            Text("No room selected", color = SonosGrey)
            return@Column
        }
        Text(if (stale) "Offline · showing last known state" else room.roomName, color = if (stale) SonosError else SonosGrey)
        ProductImage(room.modelName, Modifier.fillMaxWidth().height(230.dp))
        Text(state?.title?.takeIf { it.isNotBlank() } ?: "Nothing playing", fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(joinText(state?.artist, state?.album).ifBlank { SonosProtocol.sourceLabel(state?.source.orEmpty()) }, color = SonosGrey, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(state?.positionSeconds), color = SonosGrey, fontSize = 12.sp)
            Text(formatTime(state?.durationSeconds), color = SonosGrey, fontSize = 12.sp)
        }
        LinearProgressIndicator(
            progress = { if ((state?.durationSeconds ?: 0) > 0) (state?.positionSeconds ?: 0).toFloat() / state!!.durationSeconds!! else 0f },
            modifier = Modifier.fillMaxWidth(), color = SonosBlack, trackColor = SonosRule,
        )
        val playing = state?.transport == "PLAYING"
        val unavailable = if (playing && actions.isNotEmpty() && "Pause" !in actions) "This source cannot be paused"
            else if (!playing) SonosProtocol.playbackUnavailableReason(state?.source.orEmpty(), actions) else null
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { command(action = { client.previous(room) }) }, enabled = !working && (actions.isEmpty() || "Previous" in actions), modifier = Modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp)) { Text("Previous") }
            PlaybackButton(
                playing = playing,
                onClick = { command(action = { if (playing) client.pause(room) else client.play(room) }) },
                enabled = !working && unavailable == null,
                modifier = Modifier.size(64.dp),
            )
            TextButton(onClick = { command(action = { client.next(room) }) }, enabled = !working && (actions.isEmpty() || "Next" in actions), modifier = Modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp)) { Text("Next") }
        }
        if (unavailable != null) Text(unavailable, color = SonosGrey, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (mute) "Muted" else "Volume", modifier = Modifier.width(62.dp), fontSize = 13.sp, color = SonosGrey)
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = {
                    val target = volume.toInt()
                    command({ client.setVolume(room, target) }, rollback = { volume = committedVolume.toFloat() })
                },
                valueRange = 0f..100f,
                enabled = !working,
                modifier = Modifier.weight(1f),
            )
            IconToggleButton(
                checked = mute,
                onCheckedChange = { target ->
                    val old = mute
                    mute = target
                    command({ client.setMute(room, target) }, rollback = { mute = old })
                },
                enabled = !working,
                modifier = Modifier.size(48.dp).semantics {
                    role = Role.Button
                    selected = mute
                    stateDescription = if (mute) "Muted" else "Sound on"
                    contentDescription = if (mute) "Unmute ${room.roomName}" else "Mute ${room.roomName}"
                },
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = SonosBlack,
                    checkedContentColor = Color.White,
                    containerColor = Color.Transparent,
                    contentColor = SonosBlack,
                ),
            ) {
                SpeakerGlyph(muted = mute, size = 24.dp, color = LocalContentColor.current)
            }
        }
        if (error.isNotBlank()) Text(error, color = SonosError, fontSize = 13.sp)
        HorizontalDivider(color = SonosRule)
        Text("MUSIC", modifier = Modifier.fillMaxWidth(), color = SonosGrey, fontSize = 11.sp, letterSpacing = 1.sp)
        NavigationRow("Queue", "Browse and play the current queue", queue)
        NavigationRow("Sonos Favourites", "Browse saved favourites", favourites)
        HorizontalDivider(color = SonosRule)
        Text("ROOM", modifier = Modifier.fillMaxWidth(), color = SonosGrey, fontSize = 11.sp, letterSpacing = 1.sp)
        if (system.rooms.size > 1) NavigationRow("Manage group", "Choose rooms, then apply once", group)
        NavigationRow("Sound", "Tone, loudness and TV sound", sound)
    }
}

@Composable
private fun ItemsScreen(
    title: String,
    device: SonosDevice?,
    load: (SonosDevice) -> List<SonosDidlItem>,
    playableClient: SonosClient?,
    back: () -> Unit,
) {
    var items by remember(device?.uid) { mutableStateOf<List<SonosDidlItem>>(emptyList()) }
    var loading by remember(device?.uid) { mutableStateOf(true) }
    var message by remember(device?.uid) { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(device?.uid) {
        val current = device
        if (current == null) { loading = false; return@LaunchedEffect }
        runCatching { withContext(Dispatchers.IO) { load(current) } }
            .onSuccess { items = it }.onFailure { message = "Could not read $title" }
        loading = false
    }
    ScreenColumn {
        BackHeader(title, back)
        if (playableClient == null) Text("Read-only: service authentication remains in the Sonos app.", color = SonosGrey, fontSize = 13.sp)
        if (loading) CircularProgressIndicator(color = SonosBlack)
        items.take(50).forEachIndexed { index, item ->
            val playItem: (() -> Unit)? = if (playableClient == null || working || device == null) null else ({
                working = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { playableClient.playQueueItem(device, index) }
                    message = if (ok) "Playing ${item.title}" else "Sonos could not play that queue item"
                    working = false
                }
            })
            NavigationRow(item.title.ifBlank { "Untitled" }, item.uri ?: "No playable URI", playItem)
        }
        if (!loading && items.isEmpty()) Text("Nothing available", color = SonosGrey)
        if (message.isNotBlank()) Text(message, color = if (message.startsWith("Playing")) SonosGrey else SonosError)
    }
}

@Composable
private fun GroupScreen(client: SonosClient, system: SonosSystem, selected: SonosDevice?, back: () -> Unit) {
    var coordinatorUid by remember(selected?.uid, system.rooms) { mutableStateOf(selected?.uid ?: system.rooms.firstOrNull()?.uid.orEmpty()) }
    var members by remember(coordinatorUid) { mutableStateOf(setOf<String>()) }
    var loading by remember(coordinatorUid) { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(coordinatorUid) {
        val coordinator = system.rooms.firstOrNull { it.uid == coordinatorUid } ?: return@LaunchedEffect
        loading = true
        members = runCatching { withContext(Dispatchers.IO) { client.groupMembers(coordinator) } }.getOrDefault(setOf(coordinatorUid))
        loading = false
    }
    ScreenColumn {
        BackHeader("Manage group", back)
        Text("Coordinator", color = SonosGrey)
        system.rooms.forEach { room ->
            SelectionRow(room.roomName, coordinatorUid == room.uid, radio = true) { coordinatorUid = room.uid }
        }
        HorizontalDivider(color = SonosRule)
        Text("Rooms", color = SonosGrey)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = SonosBlack)
        system.rooms.forEach { room ->
            val locked = room.uid == coordinatorUid
            SelectionRow(room.roomName, locked || room.uid in members, radio = false, enabled = !locked && !applying) { checked ->
                members = if (checked) members + room.uid else members - room.uid
            }
        }
        Button(
            onClick = {
                if (applying || coordinatorUid.isBlank()) return@Button
                applying = true
                status = ""
                scope.launch {
                    val result = withContext(Dispatchers.IO) { client.applyGroup(system.rooms, coordinatorUid, members + coordinatorUid) }
                    status = result.message
                    applying = false
                }
            },
            enabled = !applying && coordinatorUid.isNotBlank(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(if (applying) "Applying…" else "Apply group") }
        if (status.isNotBlank()) Text(status, color = if (status.startsWith("Group applied")) SonosGrey else SonosError)
    }
}

@Composable
private fun SoundScreen(client: SonosClient, device: SonosDevice?, back: () -> Unit) {
    var bass by remember(device?.uid) { mutableFloatStateOf(0f) }
    var treble by remember(device?.uid) { mutableFloatStateOf(0f) }
    var loudness by remember(device?.uid) { mutableStateOf(false) }
    var night by remember(device?.uid) { mutableStateOf<Boolean?>(null) }
    var dialog by remember(device?.uid) { mutableStateOf<Boolean?>(null) }
    var surroundEnabled by remember(device?.uid) { mutableStateOf<Boolean?>(null) }
    var surroundMode by remember(device?.uid) { mutableStateOf<Boolean?>(null) }
    var tvLevel by remember(device?.uid) { mutableFloatStateOf(0f) }
    var musicLevel by remember(device?.uid) { mutableFloatStateOf(0f) }
    var audioDelay by remember(device?.uid) { mutableFloatStateOf(0f) }
    var hasSurrounds by remember(device?.uid) { mutableStateOf(false) }
    var loading by remember(device?.uid) { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(device?.uid) {
        val current = device ?: run { loading = false; return@LaunchedEffect }
        runCatching {
            withContext(Dispatchers.IO) {
                val theatre = client.zoneState(current)?.let { SonosProtocol.theatreFromZoneState(it, current.uid) }
                listOf(
                    client.bass(current), client.treble(current), client.loudness(current),
                    client.soundbarMode(current, "NightMode"), client.soundbarMode(current, "DialogLevel"),
                    client.eqValue(current, "SurroundEnable"), client.eqValue(current, "SurroundMode"),
                    client.eqValue(current, "SurroundLevel"), client.eqValue(current, "MusicSurroundLevel"),
                    client.eqValue(current, "AudioDelay"), theatre?.surroundUids?.isNotEmpty() == true,
                )
            }
        }.onSuccess { values ->
            bass = (values[0] as Int? ?: 0).toFloat()
            treble = (values[1] as Int? ?: 0).toFloat()
            loudness = values[2] as Boolean? ?: false
            night = values[3] as Boolean?
            dialog = values[4] as Boolean?
            surroundEnabled = (values[5] as Int?)?.let { it != 0 }
            surroundMode = (values[6] as Int?)?.let { it != 0 }
            tvLevel = (values[7] as Int? ?: 0).toFloat()
            musicLevel = (values[8] as Int? ?: 0).toFloat()
            audioDelay = (values[9] as Int? ?: 0).toFloat()
            hasSurrounds = values[10] as Boolean
        }.onFailure { status = "Could not read every sound setting" }
        loading = false
    }

    fun change(action: () -> Boolean, failed: () -> Unit = {}) {
        if (working) return
        working = true
        status = ""
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { action() } }.getOrDefault(false)
            if (!ok) { failed(); status = client.lastCommandError.ifBlank { "Setting was not applied" } }
            working = false
        }
    }

    ScreenColumn {
        BackHeader("Sound", back)
        if (device == null) { Text("No room selected", color = SonosGrey); return@ScreenColumn }
        Text(device.roomName, color = SonosGrey)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = SonosBlack)
        SettingCard {
            Text("Speaker sound", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Bass  ${bass.toInt()}", color = SonosGrey)
            Slider(bass, { bass = it }, onValueChangeFinished = { val target = bass.toInt(); change({ client.setBass(device, target) }) }, valueRange = -10f..10f, enabled = !working)
            Text("Treble  ${treble.toInt()}", color = SonosGrey)
            Slider(treble, { treble = it }, onValueChangeFinished = { val target = treble.toInt(); change({ client.setTreble(device, target) }) }, valueRange = -10f..10f, enabled = !working)
            SwitchRow("Loudness", "Fuller sound at low volume", loudness, !working) { target -> val old = loudness; loudness = target; change({ client.setLoudness(device, target) }) { loudness = old } }
        }
        if (hasSurrounds && surroundEnabled != null) SettingCard {
            Text("Surround audio", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            surroundEnabled?.let { value -> SwitchRow("Surrounds", "Use bonded rear speakers", value, !working) { target -> val old = surroundEnabled; surroundEnabled = target; change({ client.setEqValue(device, "SurroundEnable", if (target) 1 else 0) }) { surroundEnabled = old } } }
            surroundMode?.let { value -> SwitchRow("Music playback", if (value) "Full" else "Ambient", value, !working) { target -> val old = surroundMode; surroundMode = target; change({ client.setEqValue(device, "SurroundMode", if (target) 1 else 0) }) { surroundMode = old } } }
            Text("TV level  ${tvLevel.toInt()}", color = SonosGrey)
            Slider(tvLevel, { tvLevel = it }, onValueChangeFinished = { val target = tvLevel.toInt(); change({ client.setEqValue(device, "SurroundLevel", target) }) }, valueRange = -15f..15f, enabled = !working)
            Text("Music level  ${musicLevel.toInt()}", color = SonosGrey)
            Slider(musicLevel, { musicLevel = it }, onValueChangeFinished = { val target = musicLevel.toInt(); change({ client.setEqValue(device, "MusicSurroundLevel", target) }) }, valueRange = -15f..15f, enabled = !working)
        }
        if (night != null || dialog != null) SettingCard {
            Text("Home theatre", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            night?.let { value -> SwitchRow("Night Sound", "Reduce loud effects", value, !working) { target -> val old = night; night = target; change({ client.setSoundbarMode(device, "NightMode", target) }) { night = old } } }
            dialog?.let { value -> SwitchRow("Speech Enhancement", "Make voices clearer", value, !working) { target -> val old = dialog; dialog = target; change({ client.setSoundbarMode(device, "DialogLevel", target) }) { dialog = old } } }
            Text("TV dialog sync  ${audioDelay.toInt()}", color = SonosGrey)
            Slider(audioDelay, { audioDelay = it }, onValueChangeFinished = { val target = audioDelay.toInt(); change({ client.setEqValue(device, "AudioDelay", target) }) }, valueRange = 0f..5f, steps = 4, enabled = !working)
        }
        if (status.isNotBlank()) Text(status, color = SonosError)
    }
}

@Composable
private fun DetailScreen(
    client: SonosClient,
    system: SonosSystem,
    product: SonosProduct?,
    room: SonosDevice?,
    reconnect: (SonosDevice) -> Unit,
    openSound: () -> Unit,
    openGroup: () -> Unit,
    configureRoom: (SonosDevice) -> Unit,
    back: () -> Unit,
) {
    val device = product?.device ?: room
    var theatre by remember(room?.uid) { mutableStateOf<SonosTheatre?>(null) }
    var topologyRead by remember(room?.uid) { mutableStateOf(false) }
    var hardware by remember(room?.uid) { mutableStateOf<SonosDeviceSettings?>(null) }
    var status by remember(room?.uid) { mutableStateOf("") }
    var working by remember(room?.uid) { mutableStateOf(false) }
    var renameOpen by remember(room?.uid) { mutableStateOf(false) }
    var nameDraft by remember(room?.uid) { mutableStateOf(room?.roomName.orEmpty()) }
    var sonosMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(room?.uid) {
        room?.let { current ->
            val loaded = runCatching { withContext(Dispatchers.IO) {
                val zone = client.zoneState(current)
                val parsed = zone?.let { SonosProtocol.theatreFromZoneState(it, current.uid) }
                Triple(zone != null, parsed, client.deviceSettings(current))
            } }.getOrNull()
            topologyRead = loaded?.first == true
            theatre = loaded?.second
            hardware = loaded?.third
        }
    }

    fun change(action: () -> Boolean, failed: () -> Unit = {}, success: String) {
        if (working) return
        working = true
        status = ""
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { action() } }.getOrDefault(false)
            if (ok) status = success else { failed(); status = client.lastCommandError.ifBlank { "Setting was not applied" } }
            working = false
        }
    }

    ScreenColumn {
        BackHeader(if (room != null) "Room settings" else "Device details", back)
        if (device == null) { Text("Product unavailable", color = SonosGrey); return@ScreenColumn }
        if (room != null) {
            ProductImage(device.modelName, Modifier.size(width = 132.dp, height = 76.dp).align(Alignment.Start))
        } else {
            ProductImage(device.modelName, Modifier.fillMaxWidth().height(250.dp))
        }
        Text(room?.roomName ?: device.modelName, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(if (room != null) device.modelName else "Model number  ${device.modelNumber.ifBlank { "Not reported" }}", color = SonosGrey)
        if (product?.inferred == true) Text("Identified from the theatre configuration; Sonos does not expose this product directly.", color = SonosGrey)

        if (room != null) {
            SettingCard {
                Text("Controls", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                NavigationRow("Room name", room.roomName, if (working) null else ({ nameDraft = room.roomName; renameOpen = true }))
                NavigationRow("Sound", "Speaker, surround and TV sound", openSound)
                if (system.rooms.size > 1) NavigationRow("Group rooms", "Choose rooms, then apply once", openGroup)
                if (SonosProtocol.supportsTvAudio(room.modelName)) {
                    NavigationRow("Reconnect TV audio", "Restore this device’s TV audio input", if (working) null else ({ reconnect(room) }))
                }
            }
            SettingCard {
                Text("Products", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                SetupProductRow(device.modelName, "Main product", false)
                theatre?.surroundUids.orEmpty().forEach { uid ->
                    val member = system.products.firstOrNull { it.device.uid == uid }
                    SetupProductRow(member?.device?.modelName ?: "Surround", "Surround · ${if (member?.isRoutable == true) "Address discovered" else "Part of theatre setup"}", member?.inferred == true)
                }
                theatre?.subUid?.let { uid ->
                    val member = system.products.firstOrNull { it.device.uid == uid }
                    SetupProductRow(member?.device?.modelName ?: "Sonos Sub", "Sub · ${if (member?.isRoutable == true) "Address discovered" else "Part of theatre setup"}", member?.inferred == true)
                }
            }
            if (hardware?.statusLight != null || hardware?.touchControls != null) SettingCard {
                Text("Hardware", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                hardware?.statusLight?.let { value ->
                    SwitchRow("Status light", "Light on the speaker", value, !working) { target ->
                        val old = hardware
                        hardware = old?.copy(statusLight = target)
                        change({ client.setStatusLight(room, target) }, { hardware = old }, "Status light updated")
                    }
                }
                hardware?.touchControls?.let { value ->
                    SwitchRow("Touch controls", "Controls on the speaker", value, !working) { target ->
                        val old = hardware
                        hardware = old?.copy(touchControls = target)
                        change({ client.setTouchControls(room, target) }, { hardware = old }, "Touch controls updated")
                    }
                }
            }
            SettingCard {
                Text("System status", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (topologyRead) "Speaker setup available" else "Setup status unavailable", fontWeight = FontWeight.Medium, color = if (topologyRead) SonosBlack else SonosError)
                Text(if (!topologyRead) "Refresh the system and try again" else theatre?.let { "Home theatre · ${it.surroundUids.size} surrounds · ${if (it.subUid != null) "Sub connected" else "No Sub"}" } ?: "Standalone room", color = SonosGrey)
            }
            SettingCard {
                Text("Sonos setup", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Adding products, Trueplay, firmware, TV setup and voice assistants requires the Sonos app.", color = SonosGrey)
                NavigationRow("Continue in Sonos", "Open the installed Sonos app", {
                    sonosMessage = if (openSonosApp(context)) "Opened Sonos" else "Could not open the Sonos app"
                })
                if (sonosMessage.isNotBlank()) Text(sonosMessage, color = SonosGrey, fontSize = 13.sp)
            }
        } else if (product != null) {
            val owner = system.rooms.firstOrNull {
                it.uid == product.device.uid || it.roomName.equals(product.device.roomName, ignoreCase = true)
            }
            if (owner != null) {
                Text(
                    if (product.isRoomCoordinator) "This product is configured through its room."
                    else "This bonded product is configured through its room.",
                    color = SonosGrey,
                )
                NavigationRow("Configure ${owner.roomName}", "Open the room that owns this product") { configureRoom(owner) }
            }
        }
        if (status.isNotBlank()) Text(status, color = if (status.contains("failed", true) || status.contains("not applied", true)) SonosError else SonosGrey)
    }

    if (renameOpen && room != null) AlertDialog(
        onDismissRequest = { if (!working) renameOpen = false },
        title = { Text("Rename room") },
        text = { OutlinedTextField(nameDraft, { nameDraft = it.take(40) }, singleLine = true, label = { Text("Room name") }) },
        confirmButton = {
            TextButton(onClick = {
                val requested = nameDraft.trim()
                change({ client.renameRoom(room, requested) }, success = "Room renamed · refresh to update the system list")
                renameOpen = false
            }, enabled = nameDraft.isNotBlank() && !working) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { renameOpen = false }, enabled = !working) { Text("Cancel") } },
    )
}

@Composable
private fun SetupProductRow(model: String, detail: String, inferred: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        ProductImage(model, Modifier.size(width = 88.dp, height = 62.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(model, fontWeight = FontWeight.Medium)
            Text("$detail${if (inferred) " · identified from setup" else ""}", color = SonosGrey, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SettingsScreen(message: String, system: SonosSystem, refresh: () -> Unit, configure: (SonosDevice) -> Unit) {
    val context = LocalContext.current
    var appMessage by remember { mutableStateOf("") }
    ScreenColumn {
        Wordmark()
        Text("System settings", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        if (system.rooms.isNotEmpty()) SettingCard {
            Text("Rooms", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Choose a room to configure its products and settings.", color = SonosGrey)
            system.rooms.forEach { room ->
                NavigationRow(room.roomName.ifBlank { "Unnamed room" }, room.modelName) { configure(room) }
            }
        } else {
            SettingCard {
                Text("Rooms", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("No rooms found yet.", color = SonosGrey)
            }
        }
        SettingCard {
            Text("App connection", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(message, color = SonosGrey)
            TextButton(onClick = refresh, modifier = Modifier.heightIn(min = 48.dp)) { Text("Refresh system") }
        }
        SettingCard {
            Text("Sonos setup and support", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Use the Sonos app to add devices, run Trueplay, update firmware or configure TV and voice features.", color = SonosGrey)
            NavigationRow("Continue in Sonos", "Open the installed Sonos app", {
                appMessage = if (openSonosApp(context)) "Opened Sonos" else "Could not open the Sonos app"
            })
            if (appMessage.isNotBlank()) Text(appMessage, color = SonosGrey, fontSize = 13.sp)
        }
        Text("Baton 0.4.6", color = SonosGrey, fontSize = 12.sp)
    }
}

private fun openSonosApp(context: Context): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage("com.sonos.acr2")
        ?: context.packageManager.getLaunchIntentForPackage("com.sonos.acr")
        ?: return false
    return runCatching { context.startActivity(launch); true }.getOrDefault(false)
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun BackHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back, modifier = Modifier.size(48.dp).semantics { contentDescription = "Back" }) { Text("‹", fontSize = 36.sp) }
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NavigationRow(title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = SonosGrey, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Text("›", fontSize = 24.sp, color = SonosGrey)
    }
}

@Composable
private fun SelectionRow(title: String, selected: Boolean, radio: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        if (radio) RadioButton(selected, onClick = { change(true) }, enabled = enabled)
        else Checkbox(selected, onCheckedChange = change, enabled = enabled)
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(subtitle, color = SonosGrey, fontSize = 13.sp) }
        Switch(checked, change, enabled = enabled)
    }
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, SonosRule),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable
private fun PlaybackButton(
    playing: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = if (playing) "Pause" else "Play" },
    ) {
        PlaybackGlyph(playing, 24.dp, LocalContentColor.current)
    }
}

@Composable
private fun SpeakerGlyph(muted: Boolean, size: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val speaker = Path().apply {
            moveTo(this@Canvas.size.width * 0.12f, this@Canvas.size.height * 0.42f)
            lineTo(this@Canvas.size.width * 0.34f, this@Canvas.size.height * 0.42f)
            lineTo(this@Canvas.size.width * 0.62f, this@Canvas.size.height * 0.18f)
            lineTo(this@Canvas.size.width * 0.62f, this@Canvas.size.height * 0.82f)
            lineTo(this@Canvas.size.width * 0.34f, this@Canvas.size.height * 0.58f)
            lineTo(this@Canvas.size.width * 0.12f, this@Canvas.size.height * 0.58f)
            close()
        }
        drawPath(speaker, color)
        if (muted) {
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(this.size.width * 0.08f, this.size.height * 0.08f),
                end = androidx.compose.ui.geometry.Offset(this.size.width * 0.92f, this.size.height * 0.92f),
                strokeWidth = this.size.minDimension * 0.12f,
                cap = StrokeCap.Round,
            )
        } else {
            drawArc(color, 315f, 90f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(this.size.minDimension * 0.09f))
        }
    }
}

@Composable
private fun ChevronGlyph(expanded: Boolean, size: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val path = Path().apply {
            if (expanded) {
                moveTo(this@Canvas.size.width * 0.2f, this@Canvas.size.height * 0.62f)
                lineTo(this@Canvas.size.width * 0.5f, this@Canvas.size.height * 0.32f)
                lineTo(this@Canvas.size.width * 0.8f, this@Canvas.size.height * 0.62f)
            } else {
                moveTo(this@Canvas.size.width * 0.2f, this@Canvas.size.height * 0.38f)
                lineTo(this@Canvas.size.width * 0.5f, this@Canvas.size.height * 0.68f)
                lineTo(this@Canvas.size.width * 0.8f, this@Canvas.size.height * 0.38f)
            }
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(this.size.minDimension * 0.1f, cap = StrokeCap.Round))
    }
}

@Composable
private fun PlaybackGlyph(playing: Boolean, size: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        if (playing) {
            drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(this.size.width * 0.24f, this.size.height * 0.16f), size = androidx.compose.ui.geometry.Size(this.size.width * 0.18f, this.size.height * 0.68f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(this.size.width * 0.04f))
            drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(this.size.width * 0.58f, this.size.height * 0.16f), size = androidx.compose.ui.geometry.Size(this.size.width * 0.18f, this.size.height * 0.68f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(this.size.width * 0.04f))
        } else {
            val triangle = Path().apply {
                moveTo(this@Canvas.size.width * 0.33f, this@Canvas.size.height * 0.14f)
                lineTo(this@Canvas.size.width * 0.84f, this@Canvas.size.height * 0.50f)
                lineTo(this@Canvas.size.width * 0.33f, this@Canvas.size.height * 0.86f)
                close()
            }
            drawPath(triangle, color)
        }
    }
}

@Composable
private fun ProductImage(modelName: String, modifier: Modifier = Modifier) {
    // Public staging omits unverified vendor product renders.
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(modelName.ifBlank { "Baton" }, color = SonosGrey, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable private fun Wordmark(modifier: Modifier = Modifier) = Text("SONOS", fontSize = 29.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = modifier)
private fun joinText(a: String?, b: String?) = listOfNotNull(a?.takeIf(String::isNotBlank), b?.takeIf(String::isNotBlank)).joinToString(" · ")
private fun formatTime(value: Int?) = value?.let { "%d:%02d".format(it / 60, it % 60) } ?: "--:--"
private fun transportLabel(value: String?) = when (value) { "PLAYING" -> "Playing"; "PAUSED_PLAYBACK" -> "Paused"; "STOPPED" -> "Stopped"; else -> "Idle" }
private val palette = lightColorScheme(primary = SonosBlack, onPrimary = Color.White, background = SonosWhite, onBackground = SonosBlack, surface = Color.White, onSurface = SonosBlack)
@Composable private fun SonosTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = palette, content = content)
