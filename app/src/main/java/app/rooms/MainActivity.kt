package app.rooms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
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

internal enum class Page { Rooms, Now, Queue, Favourites, Group, Sound, Detail, Settings, AddSpeakers }

internal class VolumeKeyRouting {
    private val handled = mutableSetOf<Int>()

    fun onDown(keyCode: Int, delta: Int, handler: ((Int) -> Boolean)?): Boolean {
        if (delta == 0 || handler?.invoke(delta) != true) return false
        handled += keyCode
        return true
    }

    fun onUp(keyCode: Int): Boolean = handled.remove(keyCode)
}

class MainActivity : ComponentActivity() {
    private val permission: ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val volumeKeyRouting = VolumeKeyRouting()

    /** Set by the Compose tree; returns true when a volume key was handled for the selected room. */
    internal var volumeKeys: ((Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        if (Build.VERSION.SDK_INT >= 37 && !hasLocalAccess()) permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun volumeDelta(keyCode: Int) = when (keyCode) { KeyEvent.KEYCODE_VOLUME_UP -> 2; KeyEvent.KEYCODE_VOLUME_DOWN -> -2; else -> 0 }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val delta = volumeDelta(keyCode)
        if (volumeKeyRouting.onDown(keyCode, delta, volumeKeys)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (volumeKeyRouting.onUp(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun hasLocalAccess() = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun render() = setContent {
        BatonTheme {
            if (hasLocalAccess()) BatonApp(SonosClient(applicationContext), applicationContext) { volumeKeys = it }
            else PermissionScreen { permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }
        }
    }
}

@Composable
private fun PermissionScreen(allow: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Wordmark()
            Text("Find your speakers", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("Baton talks to your Sonos speakers directly over your home Wi-Fi. Nothing leaves your network.", color = Muted)
            PrimaryButton("Allow local network access", onClick = allow)
        }
    }
}

@Composable
fun BatonApp(client: SonosController, appContext: Context, registerVolumeKeys: (((Int) -> Boolean)?) -> Unit) {
    var system by remember { mutableStateOf(SonosSystem(emptyList(), emptyList())) }
    var members by remember { mutableStateOf<List<SonosZoneMember>>(emptyList()) }
    var selected by remember { mutableStateOf<SonosDevice?>(null) }
    var selectedState by remember { mutableStateOf<SonosRoomState?>(null) }
    var selectedStale by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(Page.Rooms) }
    var message by remember { mutableStateOf("Finding your speakers…") }
    var discovering by remember { mutableStateOf(false) }
    var firstDiscoveryDone by remember { mutableStateOf(false) }
    var miniBusy by remember { mutableStateOf(false) }
    var detailProduct by remember { mutableStateOf<SonosProduct?>(null) }
    var detailRoom by remember { mutableStateOf<SonosDevice?>(null) }
    var detailBackPage by remember { mutableStateOf(Page.Rooms) }
    var soundBackPage by remember { mutableStateOf(Page.Now) }
    var groupBackPage by remember { mutableStateOf(Page.Now) }
    var addBackPage by remember { mutableStateOf(Page.Settings) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    val quickPrefs = remember { appContext.getSharedPreferences("quick_connect", Context.MODE_PRIVATE) }
    var quickTargetUid by remember { mutableStateOf(quickPrefs.getString("uid", null)) }
    var quickBusy by remember { mutableStateOf(false) }
    var quickStatus by remember { mutableStateOf("") }
    var reconnectFeedback by remember { mutableStateOf<QuickConnectFeedback?>(null) }
    var volumeHud by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var hudGeneration by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    suspend fun loadTopology(from: List<SonosDevice>) {
        val fresh = withContext(Dispatchers.IO) { from.firstNotNullOfOrNull { runCatching { client.zoneMembers(it) }.getOrNull() } }
        if (fresh != null) members = fresh
    }

    fun refresh() {
        if (discovering) return
        discovering = true
        val generation = ++refreshGeneration
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { client.discoverSystem() } }
            if (generation == refreshGeneration) {
                result.onSuccess { fresh ->
                    system = fresh
                    selected = selected?.let { old -> fresh.rooms.firstOrNull { it.uid == old.uid } } ?: fresh.rooms.firstOrNull()
                    message = client.lastDiscoveryMessage
                    loadTopology(fresh.rooms)
                }.onFailure { message = "Couldn't search the network. Try again." }
                discovering = false
                firstDiscoveryDone = true
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(selected?.uid) {
        selectedState = null
        selectedStale = true
        val device = selected ?: return@LaunchedEffect
        val uid = device.uid
        while (true) {
            runCatching { withContext(Dispatchers.IO) { client.getRoomState(device) } }
                .onSuccess { if (selected?.uid == uid) { selectedState = it; selectedStale = false } }
                .onFailure { if (selected?.uid == uid) selectedStale = true }
            delay(3_000)
        }
    }
    LaunchedEffect(system.rooms.map { it.uid }) {
        while (true) { delay(10_000); loadTopology(system.rooms) }
    }

    // Hardware volume keys control the selected room (or its whole group) while Baton is open.
    val latestSelected by rememberUpdatedState(selected)
    val latestSelectedState by rememberUpdatedState(selectedState)
    val latestSelectedStale by rememberUpdatedState(selectedStale)
    val latestMembers by rememberUpdatedState(members)
    DisposableEffect(Unit) {
        var pending = 0
        var sending = false
        registerVolumeKeys { delta ->
            val room = latestSelected ?: return@registerVolumeKeys false
            if (latestSelectedStale || latestSelectedState == null) return@registerVolumeKeys false
            pending += delta
            if (!sending) {
                sending = true
                scope.launch {
                    while (pending != 0) {
                        val step = pending; pending = 0
                        val coordinator = latestMembers.firstOrNull { it.uid == room.uid }?.groupCoordinator
                        val grouped = coordinator != null && latestMembers.count { it.groupCoordinator == coordinator && !it.invisible && !it.satellite } > 1
                        val newVolume = withContext(Dispatchers.IO) { runCatching { client.nudgeVolume(room, step, grouped) }.getOrNull() }
                        if (newVolume != null) {
                            volumeHud = (if (grouped) "${room.roomName} + group" else room.roomName) to newVolume
                            if (!grouped) selectedState = selectedState?.copy(volume = newVolume)
                            val gen = ++hudGeneration
                            scope.launch { delay(1_600); if (gen == hudGeneration) volumeHud = null }
                        } else {
                            pending = 0
                            if (selected?.uid == room.uid) {
                                selectedStale = true
                                scope.launch { snackbarHostState.showSnackbar("Speaker didn't respond. Phone volume keys are available again.") }
                            }
                        }
                    }
                    sending = false
                }
            }
            true
        }
        onDispose { registerVolumeKeys(null) }
    }

    BackHandler(enabled = page != Page.Rooms) {
        page = when (page) {
            Page.Queue, Page.Favourites -> Page.Now
            Page.Group -> groupBackPage
            Page.Sound -> soundBackPage
            Page.Detail -> detailBackPage
            Page.AddSpeakers -> addBackPage
            Page.Now, Page.Settings, Page.Rooms -> Page.Rooms
        }
    }

    if (system.rooms.isEmpty() && !firstDiscoveryDone) { DiscoveryScreen(); return }

    fun miniPlayPause() {
        val device = selected ?: return
        if (miniBusy) return
        miniBusy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { if (selectedState?.transport == "PLAYING") client.pause(device) else client.play(device) }
            if (ok) runCatching { withContext(Dispatchers.IO) { client.getRoomState(device) } }.onSuccess { if (selected?.uid == device.uid) selectedState = it }
            else message = client.lastCommandError.ifBlank { "Playback command failed" }
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
            } finally { quickBusy = false }
        }
    }

    fun openRoomSettings(room: SonosDevice, from: Page) {
        selected = room
        detailRoom = room
        detailProduct = system.products.firstOrNull { it.device.uid == room.uid }
        detailBackPage = from
        page = Page.Detail
    }

    fun openAddSpeakers(from: Page) { addBackPage = from; page = Page.AddSpeakers }

    Scaffold(
        containerColor = Paper,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                if (selected != null && page != Page.Now && page != Page.AddSpeakers) {
                    MiniPlayer(client, selected!!, selectedState, selectedStale, miniBusy, { page = Page.Now }, ::miniPlayPause)
                }
                NavigationBar(containerColor = Color.White) {
                    val tabs = listOf(Page.Rooms to "Rooms", Page.Now to "Now Playing", Page.Settings to "Settings")
                    tabs.forEach { (destination, label) ->
                        val home = when (page) {
                            Page.Queue, Page.Favourites -> Page.Now
                            Page.Group -> if (groupBackPage == Page.Now) Page.Now else detailBackPage
                            Page.Sound -> if (soundBackPage == Page.Now) Page.Now else detailBackPage
                            Page.Detail -> detailBackPage
                            Page.AddSpeakers -> if (addBackPage == Page.Detail) detailBackPage else addBackPage
                            else -> page
                        }
                        val active = home == destination
                        NavigationBarItem(
                            modifier = Modifier.testTag("tab_${destination.name}"),
                            selected = active,
                            onClick = { page = destination },
                            icon = {
                                val tint = LocalContentColor.current
                                when (destination) {
                                    Page.Rooms -> HomeGlyph(22.dp, tint)
                                    Page.Now -> PlaybackGlyph(false, 20.dp, tint)
                                    else -> SlidersGlyph(22.dp, tint)
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
                Page.Rooms -> RoomsScreen(client, system, members, message, discovering, ::refresh, quickTargetUid, quickBusy, quickStatus,
                    selectQuickTarget = { target ->
                        quickPrefs.edit { putString("uid", target.uid); putString("name", target.roomName) }
                        quickTargetUid = target.uid
                        quickStatus = ""
                    },
                    quickConnect = { quickTargetUid?.let { uid -> system.rooms.firstOrNull { it.uid == uid } }?.let(::reconnect) },
                    openRoom = { selected = it; page = Page.Now },
                    roomDetail = { openRoomSettings(it, Page.Rooms) },
                    addSpeakers = { openAddSpeakers(Page.Rooms) },
                )
                Page.Now -> NowPlayingScreen(client, system, members, selected, selectedState, selectedStale,
                    choose = { selected = it }, update = { selectedState = it; selectedStale = false },
                    queue = { page = Page.Queue }, favourites = { page = Page.Favourites },
                    group = { groupBackPage = Page.Now; page = Page.Group }, sound = { soundBackPage = Page.Now; page = Page.Sound },
                )
                Page.Queue -> QueueScreen(client, selected) { page = Page.Now }
                Page.Favourites -> FavouritesScreen(client, selected, played = { page = Page.Now }) { page = Page.Now }
                Page.Group -> GroupScreen(client, system, members, selected, applied = { scope.launch { loadTopology(system.rooms) } }) { page = groupBackPage }
                Page.Sound -> SoundScreen(client, selected, members) { page = soundBackPage }
                Page.Detail -> DetailScreen(client, system, members, detailProduct, detailRoom,
                    reconnect = ::reconnect,
                    openSound = { soundBackPage = Page.Detail; page = Page.Sound },
                    openGroup = { groupBackPage = Page.Detail; page = Page.Group },
                    configureRoom = { target -> selected = target; detailRoom = target; detailProduct = system.products.firstOrNull { it.device.uid == target.uid } },
                    arrange = { openAddSpeakers(Page.Detail) },
                    renamed = ::refresh,
                    back = { page = detailBackPage })
                Page.Settings -> SettingsScreen(message, system, members, discovering, ::refresh, addSpeakers = { openAddSpeakers(Page.Settings) }) { openRoomSettings(it, Page.Settings) }
                Page.AddSpeakers -> AddSpeakersScreen(client, system, members, discovering, ::refresh,
                    changed = { scope.launch { loadTopology(system.rooms) }; refresh() }) { page = addBackPage }
            }
            AnimatedVisibility(volumeHud != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)) {
                volumeHud?.let { (name, level) ->
                    Surface(shape = RoundedCornerShape(24.dp), color = Ink, shadowElevation = 6.dp) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SpeakerGlyph(level == 0, 18.dp, Color.White)
                            Text(name, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                            LinearProgressIndicator(progress = { level / 100f }, modifier = Modifier.width(80.dp), color = Color.White, trackColor = Color(0x55FFFFFF))
                            Text("$level", color = Color.White, fontSize = 13.sp)
                        }
                    }
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
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); seconds++ } }
    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.statusBarsPadding().fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Wordmark()
            Spacer(Modifier.height(28.dp))
            CircularProgressIndicator(color = Ink, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(20.dp))
            Text("Finding your speakers", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (seconds >= 6) {
                Spacer(Modifier.height(8.dp))
                Text("Still looking. Make sure this phone is on the same Wi-Fi as your speakers.", color = Muted, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun MiniPlayer(client: SonosController, room: SonosDevice, state: SonosRoomState?, stale: Boolean, busy: Boolean, open: () -> Unit, playPause: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickableRole("Open Now Playing", open).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(client, state?.artworkUri.orEmpty(), room.modelName, Modifier.size(44.dp), corner = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(state?.title?.takeIf { it.isNotBlank() } ?: SonosProtocol.sourceLabel(state?.source.orEmpty()), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text("${room.roomName}${if (stale) " · offline" else ""}", color = if (stale) Danger else Muted, fontSize = 12.sp, maxLines = 1)
            }
            if (!SonosProtocol.isTvSource(state?.source.orEmpty())) PlaybackButton(state?.transport == "PLAYING", playPause, !busy, Modifier.size(48.dp))
        }
    }
}

@Composable
internal fun PlaybackButton(playing: Boolean, onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = modifier.described(if (playing) "Pause" else "Play")) {
        PlaybackGlyph(playing, 22.dp, LocalContentColor.current)
    }
}
