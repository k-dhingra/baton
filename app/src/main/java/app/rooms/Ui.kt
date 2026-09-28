package app.rooms

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rooms.sonos.SonosController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val Paper = Color(0xFFFAFAFA)
internal val Ink = Color(0xFF101010)
internal val Muted = Color(0xFF6C6C68)
internal val Rule = Color(0xFFE4E4E1)
internal val Danger = Color(0xFFB3261E)
internal val Good = Color(0xFF1E6B3A)

/** Status line with an explicit tone. No more guessing error colour from message text. */
data class Note(val text: String, val error: Boolean = false) {
    companion object {
        val None = Note("")
        fun ok(text: String) = Note(text, false)
        fun fail(text: String) = Note(text, true)
    }
}

@Composable
internal fun NoteText(note: Note, modifier: Modifier = Modifier) {
    if (note.text.isNotBlank()) Text(note.text, color = if (note.error) Danger else Muted, fontSize = 13.sp, modifier = modifier)
}

private val Wash = Color(0xFFEDEDEA)
private val palette = lightColorScheme(
    primary = Ink, onPrimary = Color.White, primaryContainer = Wash, onPrimaryContainer = Ink,
    secondary = Ink, onSecondary = Color.White, secondaryContainer = Wash, onSecondaryContainer = Ink,
    tertiary = Ink, onTertiary = Color.White, tertiaryContainer = Wash, onTertiaryContainer = Ink,
    background = Paper, onBackground = Ink, surface = Color.White, onSurface = Ink,
    surfaceVariant = Wash, onSurfaceVariant = Muted, outline = Rule, outlineVariant = Rule,
    surfaceContainerHighest = Wash, surfaceContainerHigh = Color(0xFFF2F2F0), surfaceContainer = Color(0xFFF5F5F3),
)
@Composable internal fun BatonTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = palette, content = content)

@Composable
internal fun Wordmark(modifier: Modifier = Modifier) =
    Text("Baton", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, modifier = modifier)

@Composable
internal fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun BackHeader(title: String, back: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) {
            IconButton(onClick = back, modifier = Modifier.size(48.dp).semantics { contentDescription = "Back" }) {
                BackGlyph(22.dp, Ink)
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() },
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SectionLabel(text: String) =
    Text(text.uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).semantics { heading() })

@Composable
internal fun NavigationRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    trailing: @Composable () -> Unit = { ChevronRight(18.dp, Muted) },
    emphasised: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).semantics { role = Role.Button } else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }; Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = if (emphasised) FontWeight.Bold else FontWeight.SemiBold, color = if (onClick == null && leading != null) Muted else Ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
internal fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun ChoiceRow(title: String, subtitle: String, selected: Boolean, enabled: Boolean = true, multi: Boolean = false, image: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp))
            .selectable(selected, enabled = enabled, role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (image != null) { ProductImage(image, Modifier.size(width = 64.dp, height = 46.dp)); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = if (enabled) Ink else Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (multi) Checkbox(selected, onCheckedChange = null, enabled = enabled)
        else RadioButton(selected, onClick = null, enabled = enabled)
    }
}

@Composable
internal fun SmallAction(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Rule), contentPadding = PaddingValues(horizontal = 14.dp)) { Text(text, color = if (enabled) Ink else Muted, fontSize = 14.sp) }

@Composable
internal fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Rule),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable internal fun CardTitle(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text(text, fontWeight = FontWeight.SemiBold) }

@Composable
internal fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Rule)) { Text(text, color = if (enabled) Ink else Muted) }

@Composable
internal fun ProductImage(modelName: String, modifier: Modifier = Modifier) {
    // Vendor product renders are not redistributed without a verified licence.
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(modelName.ifBlank { "Speaker" }, color = Muted, fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Album art from the speaker, falling back to the product image. Cached per URL for the session. */
private val artCache = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 24
}

@Composable
internal fun Artwork(client: SonosController, url: String, fallbackModel: String, modifier: Modifier, corner: Dp = 16.dp) {
    var bitmap by remember(url) { mutableStateOf(synchronized(artCache) { artCache[url] }) }
    LaunchedEffect(url) {
        if (url.isBlank() || bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            client.artwork(url)?.let { bytes -> runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull() }
        }
        if (loaded != null) { synchronized(artCache) { artCache[url] = loaded }; bitmap = loaded }
    }
    val art = bitmap
    if (art != null) Image(art, contentDescription = "Album art", contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(corner)))
    else ProductImage(fallbackModel, modifier)
}

// ---- Glyphs (drawn, so no icon dependency) ------------------------------------------------

@Composable
internal fun SpeakerGlyph(muted: Boolean, size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val speaker = Path().apply {
            moveTo(w * 0.12f, h * 0.40f); lineTo(w * 0.32f, h * 0.40f); lineTo(w * 0.58f, h * 0.18f)
            lineTo(w * 0.58f, h * 0.82f); lineTo(w * 0.32f, h * 0.60f); lineTo(w * 0.12f, h * 0.60f); close()
        }
        drawPath(speaker, color)
        if (muted) drawLine(color, Offset(w * 0.08f, h * 0.08f), Offset(w * 0.92f, h * 0.92f), strokeWidth = this.size.minDimension * 0.1f, cap = StrokeCap.Round)
        else drawArc(color, 300f, 120f, false, topLeft = Offset(w * 0.40f, h * 0.24f), size = Size(w * 0.44f, h * 0.52f), style = Stroke(this.size.minDimension * 0.08f, cap = StrokeCap.Round))
    }
}

@Composable
internal fun ChevronGlyph(expanded: Boolean, size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val path = Path().apply {
            if (expanded) { moveTo(w * .2f, h * .62f); lineTo(w * .5f, h * .32f); lineTo(w * .8f, h * .62f) }
            else { moveTo(w * .2f, h * .38f); lineTo(w * .5f, h * .68f); lineTo(w * .8f, h * .38f) }
        }
        drawPath(path, color, style = Stroke(this.size.minDimension * 0.1f, cap = StrokeCap.Round))
    }
}

@Composable
internal fun ChevronRight(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawPath(Path().apply { moveTo(w * .38f, h * .2f); lineTo(w * .68f, h * .5f); lineTo(w * .38f, h * .8f) }, color, style = Stroke(this.size.minDimension * 0.1f, cap = StrokeCap.Round))
    }
}

@Composable
internal fun BackGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawPath(Path().apply { moveTo(w * .62f, h * .18f); lineTo(w * .3f, h * .5f); lineTo(w * .62f, h * .82f) }, color, style = Stroke(this.size.minDimension * 0.11f, cap = StrokeCap.Round))
    }
}

@Composable
internal fun PlaybackGlyph(playing: Boolean, size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        if (playing) {
            drawRoundRect(color, Offset(w * .24f, h * .16f), Size(w * .18f, h * .68f), CornerRadius(w * .04f))
            drawRoundRect(color, Offset(w * .58f, h * .16f), Size(w * .18f, h * .68f), CornerRadius(w * .04f))
        } else drawPath(Path().apply { moveTo(w * .33f, h * .14f); lineTo(w * .84f, h * .5f); lineTo(w * .33f, h * .86f); close() }, color)
    }
}

@Composable
internal fun SkipGlyph(forward: Boolean, size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        if (forward) {
            drawPath(Path().apply { moveTo(w * .2f, h * .18f); lineTo(w * .66f, h * .5f); lineTo(w * .2f, h * .82f); close() }, color)
            drawRoundRect(color, Offset(w * .7f, h * .18f), Size(w * .1f, h * .64f), CornerRadius(w * .03f))
        } else {
            drawPath(Path().apply { moveTo(w * .8f, h * .18f); lineTo(w * .34f, h * .5f); lineTo(w * .8f, h * .82f); close() }, color)
            drawRoundRect(color, Offset(w * .2f, h * .18f), Size(w * .1f, h * .64f), CornerRadius(w * .03f))
        }
    }
}

@Composable
internal fun HomeGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawPath(Path().apply {
            moveTo(w * .14f, h * .48f); lineTo(w * .5f, h * .16f); lineTo(w * .86f, h * .48f)
            moveTo(w * .24f, h * .40f); lineTo(w * .24f, h * .84f); lineTo(w * .76f, h * .84f); lineTo(w * .76f, h * .40f)
        }, color, style = Stroke(this.size.minDimension * 0.09f, cap = StrokeCap.Round))
    }
}

@Composable
internal fun SlidersGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = this.size.minDimension * 0.08f
        listOf(.28f to .34f, .5f to .66f, .72f to .44f).forEach { (y, knob) ->
            drawLine(color, Offset(w * .14f, h * y), Offset(w * .86f, h * y), s, StrokeCap.Round)
            drawCircle(color, this.size.minDimension * 0.09f, Offset(w * knob, h * y))
        }
    }
}

@Composable
internal fun PlusGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = this.size.minDimension * 0.1f
        drawLine(color, Offset(w * .5f, h * .18f), Offset(w * .5f, h * .82f), s, StrokeCap.Round)
        drawLine(color, Offset(w * .18f, h * .5f), Offset(w * .82f, h * .5f), s, StrokeCap.Round)
    }
}

@Composable
internal fun RefreshGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val s = this.size.minDimension * 0.09f
        drawArc(color, 40f, 290f, false, topLeft = Offset(w * .18f, h * .18f), size = Size(w * .64f, h * .64f), style = Stroke(s, cap = StrokeCap.Round))
        drawPath(Path().apply { moveTo(w * .86f, h * .20f); lineTo(w * .84f, h * .44f); lineTo(w * .62f, h * .38f) }, color, style = Stroke(s, cap = StrokeCap.Round))
    }
}

@Composable
internal fun CheckGlyph(size: Dp, color: Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawPath(Path().apply { moveTo(w * .2f, h * .52f); lineTo(w * .42f, h * .74f); lineTo(w * .82f, h * .28f) }, color, style = Stroke(this.size.minDimension * 0.1f, cap = StrokeCap.Round))
    }
}

@Composable
internal fun StepBadge(number: Int, done: Boolean) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).then(Modifier), contentAlignment = Alignment.Center) {
        Surface(color = if (done) Ink else Color.White, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.5.dp, Ink), modifier = Modifier.fillMaxSize()) {}
        if (done) CheckGlyph(16.dp, Color.White) else Text("$number", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

// ---- Sonos app handoff --------------------------------------------------------------------

internal fun sonosInstalled(context: Context) =
    context.packageManager.getLaunchIntentForPackage("com.sonos.acr2") != null || context.packageManager.getLaunchIntentForPackage("com.sonos.acr") != null

internal fun openSonosApp(context: Context): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage("com.sonos.acr2")
        ?: context.packageManager.getLaunchIntentForPackage("com.sonos.acr")
    if (launch != null) return runCatching { context.startActivity(launch); true }.getOrDefault(false)
    val marketOpened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=com.sonos.acr2".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
    if (marketOpened) return true
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=com.sonos.acr2".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}

internal fun Modifier.clickableRole(label: String, onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick).semantics { role = Role.Button; contentDescription = label }

internal fun Modifier.described(label: String): Modifier = this.semantics { contentDescription = label }

internal fun joinText(a: String?, b: String?) = listOfNotNull(a?.takeIf(String::isNotBlank), b?.takeIf(String::isNotBlank)).joinToString(" · ")
internal fun formatTime(value: Int?) = value?.let { "%d:%02d".format(it / 60, it % 60) } ?: "--:--"
internal fun transportLabel(value: String?) = when (value) { "PLAYING" -> "Playing"; "PAUSED_PLAYBACK" -> "Paused"; "STOPPED" -> "Stopped"; else -> "Idle" }
