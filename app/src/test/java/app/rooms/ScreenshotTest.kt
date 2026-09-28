package app.rooms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.rooms.sonos.SonosSystem
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders every screen with synthetic fixtures so the UI can be reviewed without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun settle() = repeat(12) { rule.mainClock.advanceTimeBy(250); Thread.sleep(60) }

    private fun shot(name: String) {
        settle()
        rule.onRoot().captureRoboImage("build/screens/$name.png")
        java.io.File("build/screens/$name.audit.txt").writeText(LayoutAudit.run(rule).joinToString("\n").ifBlank { "clean" })
    }

    private fun app(fake: FakeController = FakeController()) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            BatonTheme { BatonApp(fake, ApplicationProvider.getApplicationContext()) {} }
        }
    }

    private fun screen(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { BatonTheme { Surface(Modifier.fillMaxSize(), color = Paper) { Box { content() } } } }
    }

    private fun tap(text: String) { settle(); rule.onAllNodesWithText(text, substring = true)[0].performScrollTo().performClick(); settle() }
    private fun tab(name: String) { settle(); rule.onNodeWithTag("tab_$name").performClick(); settle() }
    private fun tapNoScroll(text: String, exact: Boolean = false) { settle(); rule.onAllNodesWithText(text, substring = !exact)[0].performClick(); settle() }

    @Test fun rooms() { app(); shot("01_rooms") }
    @Test fun roomsLong() { RuntimeEnvironment.setFontScale(1.3f); app(FakeController(longNames = true)); shot("02_rooms_long_large_text") }
    @Test fun nowPlayingGroup() { app(); tapNoScroll("Room B + Room C"); shot("03_now_playing_group") }
    @Test fun nowPlayingTv() { app(); tapNoScroll("Room A"); shot("04_now_playing_tv") }
    @Test fun settings() { app(); tab("Settings"); shot("05_settings") }
    @Test fun addHub() { app(); tab("Settings"); tap("Add or arrange speakers"); shot("06_add_hub") }
    @Test fun addHubNoSpares() { app(FakeController(spares = false)); tab("Settings"); tap("Add or arrange speakers"); shot("07_add_hub_no_spares") }
    @Test fun addSub() { app(); tab("Settings"); tap("Add or arrange speakers"); tap("Add a Sub"); shot("08_add_sub") }
    @Test fun addSubReview() { app(); tab("Settings"); tap("Add or arrange speakers"); tap("Add a Sub"); tap("Review"); shot("09_add_sub_review") }
    @Test fun stereo() { app(); tab("Settings"); tap("Add or arrange speakers"); tap("Make a stereo pair"); tap("Room C"); tap("Room D"); shot("10_stereo") }
    @Test fun newSpeaker() { app(); tab("Settings"); tap("Add or arrange speakers"); tap("New Sonos speaker"); shot("11_new_speaker") }

    private val fake = FakeController()
    @Test fun sound() { screen { SoundScreen(fake, fake.arc, fake.members) {} }; shot("12_sound") }
    @Test fun detail() {
        screen { DetailScreen(fake, fake.discoverSystem(), fake.members, null, fake.arc, {}, {}, {}, {}, {}, {}, {}) }
        shot("13_room_settings")
    }
    @Test fun queue() { screen { QueueScreen(fake, fake.beam) {} }; shot("14_queue") }
    @Test fun favourites() { screen { FavouritesScreen(fake, fake.beam, {}) {} }; shot("15_favourites") }
    @Test fun group() { screen { GroupScreen(fake, fake.discoverSystem(), fake.members, fake.beam, {}) {} }; shot("16_group") }
    @Test fun emptySystem() {
        val empty = object : app.rooms.sonos.SonosController by FakeController() {
            override fun discoverSystem() = SonosSystem(emptyList(), emptyList())
            override val lastDiscoveryMessage = "No Sonos replies · multicast and local scan tried"
        }
        rule.mainClock.autoAdvance = false
        rule.setContent { BatonTheme { BatonApp(empty, ApplicationProvider.getApplicationContext()) {} } }
        shot("17_empty")
    }
}
