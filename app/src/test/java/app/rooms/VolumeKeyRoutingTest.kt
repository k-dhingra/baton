package app.rooms

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeyRoutingTest {
    @Test fun `unhandled volume key leaves system key up available`() {
        val routing = VolumeKeyRouting()
        assertFalse(routing.onDown(KeyEvent.KEYCODE_VOLUME_UP, 2, null))
        assertFalse(routing.onUp(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(routing.onDown(KeyEvent.KEYCODE_VOLUME_DOWN, -2) { false })
        assertFalse(routing.onUp(KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    @Test fun `handled volume key consumes only its matching key up`() {
        val routing = VolumeKeyRouting()
        assertTrue(routing.onDown(KeyEvent.KEYCODE_VOLUME_UP, 2) { true })
        assertFalse(routing.onUp(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(routing.onUp(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(routing.onUp(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test fun `unrelated keys never reach the volume handler`() {
        val routing = VolumeKeyRouting()
        assertFalse(routing.onDown(KeyEvent.KEYCODE_ENTER, 0) { error("Unexpected volume handler call") })
        assertFalse(routing.onUp(KeyEvent.KEYCODE_ENTER))
    }
}
