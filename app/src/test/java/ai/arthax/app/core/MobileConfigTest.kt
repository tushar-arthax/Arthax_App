package ai.arthax.app.core

import ai.arthax.app.data.remote.dto.MobileConfigResponseDto
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server config is parsed fail-safe: anything the server sends that the app cannot
 * use keeps the shipped default, and nothing it sends can make the app throw. Bodies here
 * are the backend's own shape (`{"version":…,"updated_at":…,"config":{…}}`).
 */
class MobileConfigTest {

    private val adapter = Moshi.Builder().build().adapter(MobileConfigResponseDto::class.java)

    private fun parse(json: String): MobileConfig = MobileConfig.from(adapter.fromJson(json)!!)

    private val full = """
        {"version": 4, "updated_at": "2026-09-18T10:00:00", "config": {
            "lookback_hours": 48,
            "sync_interval_minutes": 30,
            "heartbeat_interval_minutes": 120,
            "ring_allowance_sec": 40,
            "max_duration_slack_sec": 25,
            "unmatched_retention_days": 10,
            "by_phone_recheck_hours": 12,
            "extra_folders": ["Recordings/Work", " Music/Calls/ "],
            "recorder_profiles": [
                {"manufacturer_match": "samsung", "duration_semantics": "from_answer",
                 "extensions": [".m4a", ".3ga"], "folders": ["Recordings/Call", "Call"],
                 "filename_patterns": [{"regex": "^(?<label>.*)_(?<ts>\\d{6}_\\d{6})$", "timestamp_format": "yyMMdd_HHmmss"}]},
                {"manufacturer_match": "xiaomi|redmi|poco", "duration_semantics": "from_dial",
                 "extensions": [".mp3"], "folders": ["MIUI/sound_recorder/call_rec"]}
            ]
        }}
    """.trimIndent()

    @Test
    fun `every knob is read from the server`() {
        val config = parse(full)

        assertEquals(4, config.version)
        assertEquals(48, config.lookbackHours)
        assertEquals(30, config.syncIntervalMinutes)
        assertEquals(120, config.heartbeatIntervalMinutes)
        assertEquals(40, config.ringAllowanceSec)
        assertEquals(25, config.maxDurationSlackSec)
        assertEquals(10, config.unmatchedRetentionDays)
        assertEquals(12, config.byPhoneRecheckHours)
        assertEquals(listOf("Recordings/Work", "Music/Calls/"), config.extraFolders)
        assertEquals(2, config.recorderProfiles.size)
    }

    @Test
    fun `the shipped defaults match the backend's defaults`() {
        // DEFAULT_MOBILE_CONFIG in mobile_config_service.py. A phone that has never reached
        // the server behaves exactly as one on the default server config.
        val d = MobileConfig.DEFAULTS
        assertEquals(0, d.version)
        assertEquals(72, d.lookbackHours)
        assertEquals(15, d.syncIntervalMinutes)
        assertEquals(60, d.heartbeatIntervalMinutes)
        assertEquals(35, d.ringAllowanceSec)
        assertEquals(20, d.maxDurationSlackSec)
        assertEquals(7, d.unmatchedRetentionDays)
        assertEquals(6, d.byPhoneRecheckHours)
        assertEquals(72L * 60 * 60 * 1000, d.lookbackMillis)
    }

    @Test
    fun `a missing config block keeps every default`() {
        val config = parse("""{"version": 2}""")

        assertEquals(2, config.version)
        assertEquals(MobileConfig.DEFAULTS.copy(version = 2), config)
    }

    @Test
    fun `an out-of-range value is clamped to the backend's own bounds`() {
        val config = parse("""{"version": 3, "config": {"lookback_hours": 5000, "heartbeat_interval_minutes": 1}}""")

        assertEquals(720, config.lookbackHours)
        assertEquals(5, config.heartbeatIntervalMinutes)
    }

    @Test
    fun `a profile whose pattern does not compile is skipped, the rest survive`() {
        val config = parse(
            """{"version": 5, "config": {"recorder_profiles": [
                {"manufacturer_match": "(", "duration_semantics": "from_dial", "extensions": [], "folders": ["X"]},
                {"manufacturer_match": "vivo", "duration_semantics": "nonsense", "extensions": [".amr"], "folders": ["Record/Call"]}
            ]}}""",
        )

        assertEquals(1, config.recorderProfiles.size)
        val vivo = config.recorderProfiles.single()
        assertTrue(vivo.matches("vivo"))
        assertEquals("unknown semantics fall back to from_dial", MobileConfig.FROM_DIAL, vivo.durationSemantics)
        assertEquals(listOf("amr"), vivo.extensions)
    }

    @Test
    fun `the manufacturer match is case-insensitive and a substring`() {
        val config = parse(full)

        assertNotNull(config.profileFor("Samsung"))
        assertNotNull(config.profileFor("XIAOMI"))
        assertNotNull(config.profileFor("Redmi"))
        assertNull(config.profileFor("OnePlus"))
        assertNull(config.profileFor(null))
    }

    @Test
    fun `folders for a phone are its profile's folders plus the extras, de-duplicated`() {
        val config = parse(full)

        assertEquals(
            listOf("Recordings/Call", "Call", "Recordings/Work", "Music/Calls"),
            config.foldersFor("samsung"),
        )
        assertEquals("an unknown phone still gets the extras", listOf("Recordings/Work", "Music/Calls"), config.foldersFor("nokia"))
    }

    @Test
    fun `a record-from-answer phone gets no ring allowance`() {
        val config = parse(full)

        assertEquals(0, config.ringAllowanceSecFor("samsung"))
        assertEquals(40, config.ringAllowanceSecFor("xiaomi"))
        assertEquals("unknown phones keep the shipped behaviour", 40, config.ringAllowanceSecFor("nokia"))
    }

    @Test
    fun `unknown keys are ignored rather than fatal`() {
        val config = parse("""{"version": 6, "config": {"lookback_hours": 24, "brand_new_knob": true}}""")

        assertEquals(24, config.lookbackHours)
    }
}
