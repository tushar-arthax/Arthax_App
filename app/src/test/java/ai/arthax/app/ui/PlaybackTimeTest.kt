package ai.arthax.app.ui

import ai.arthax.app.ui.common.formatPlaybackTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** The clock under the recording scrubber. */
class PlaybackTimeTest {

    @Test
    fun `formats under a minute with a leading zero`() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("0:05", formatPlaybackTime(5_000))
        assertEquals("0:59", formatPlaybackTime(59_999))
    }

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("1:00", formatPlaybackTime(60_000))
        assertEquals("1:14", formatPlaybackTime(74_000))
        assertEquals("12:07", formatPlaybackTime(727_000))
    }

    /**
     * MediaPlayer reports -1 for a stream whose duration it cannot determine, and the
     * clock must read 0:00 rather than "-1:-1".
     */
    @Test
    fun `an unknown duration reads as zero rather than a negative clock`() {
        assertEquals("0:00", formatPlaybackTime(-1))
    }
}
