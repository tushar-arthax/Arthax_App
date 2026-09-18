package com.example.arthax.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band a recording has to fit before it is uploaded under a lead's name.
 *
 * Time and file name pick the file; this decides whether the pick was plausible. The
 * defaults mirror the server config: 0.8x below, talk + 35s of ringing + 20s of slack
 * above, and nothing over five seconds for a call nobody answered.
 */
class RecordingSanityTest {

    private fun check(
        audio: Double?,
        talk: Long,
        connected: Boolean = true,
        ring: Int = 35,
        slack: Int = 20,
        estimated: Boolean = false,
    ) = RecordingSanity.check(
        audioSeconds = audio,
        estimated = estimated,
        talkSeconds = talk,
        connected = connected,
        ringAllowanceSec = ring,
        slackSec = slack,
    )

    @Test
    fun `audio about as long as the call is uploaded`() {
        assertEquals(RecordingSanity.Verdict.Upload, check(audio = 120.0, talk = 120))
    }

    @Test
    fun `audio at exactly eighty percent is still uploaded`() {
        assertEquals(RecordingSanity.Verdict.Upload, check(audio = 96.0, talk = 120))
    }

    @Test
    fun `audio shorter than eighty percent of the call is held`() {
        val verdict = check(audio = 60.0, talk = 120)

        assertTrue(verdict is RecordingSanity.Verdict.Review)
        assertTrue((verdict as RecordingSanity.Verdict.Review).reason.contains("60s"))
        assertTrue(verdict.reason.contains("120s"))
    }

    @Test
    fun `ringing on a record-from-dial phone is allowed`() {
        // Xiaomi starts recording at dial-out: 30s of ringing before a 120s talk is normal.
        assertEquals(RecordingSanity.Verdict.Upload, check(audio = 150.0, talk = 120))
    }

    @Test
    fun `audio at the top of the band is uploaded and one second over is held`() {
        assertEquals(RecordingSanity.Verdict.Upload, check(audio = 175.0, talk = 120))
        assertTrue(check(audio = 176.0, talk = 120) is RecordingSanity.Verdict.Review)
    }

    @Test
    fun `a record-from-answer phone gets no ring allowance`() {
        // Samsung's audio starts when the call connects, so the server sets the allowance
        // to zero for it: 120s of talk cannot legitimately be 150s of audio.
        assertEquals(RecordingSanity.Verdict.Upload, check(audio = 130.0, talk = 120, ring = 0))
        assertTrue(check(audio = 150.0, talk = 120, ring = 0) is RecordingSanity.Verdict.Review)
    }

    @Test
    fun `an unanswered call with more than five seconds of audio is held`() {
        assertEquals(RecordingSanity.Verdict.Upload, check(audio = 3.0, talk = 0, connected = false))
        assertTrue(check(audio = 12.0, talk = 0, connected = false) is RecordingSanity.Verdict.Review)
    }

    @Test
    fun `an unreadable file is not held on a guess`() {
        // The probe failed and there was no size to go on. The time-and-name match already
        // chose this file; refusing it would lose real recordings on odd containers.
        assertEquals(RecordingSanity.Verdict.Upload, check(audio = null, talk = 120))
    }

    @Test
    fun `an estimated length says so in the reason`() {
        val verdict = check(audio = 30.0, talk = 120, estimated = true) as RecordingSanity.Verdict.Review

        assertTrue(verdict.reason.contains("estimated"))
    }

    @Test
    fun `the size estimate is coarse but in the right order of magnitude`() {
        // Two minutes of AAC at ~64 kbps is about a megabyte.
        val seconds = RecordingSanity.estimateSeconds(sizeBytes = 960_000, mimeType = "audio/mp4")!!

        assertTrue("got $seconds", seconds in 100.0..140.0)
        assertEquals(null, RecordingSanity.estimateSeconds(sizeBytes = 0, mimeType = "audio/mp4"))
    }
}
