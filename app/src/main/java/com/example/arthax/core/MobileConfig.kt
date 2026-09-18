package com.example.arthax.core

import com.example.arthax.data.remote.dto.MobileConfigDto
import com.example.arthax.data.remote.dto.MobileConfigResponseDto
import com.example.arthax.data.remote.dto.RecorderProfileDto
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Server-driven tunables — the typed, validated form of GET /api/mobile/config.
 *
 * Every new phone model in the field (a Vivo with a different recorder folder, a Samsung
 * build that stamps its files differently) used to need a new APK sideloaded to every
 * rep. Now the operator edits one document in the CRM and the phones pick it up on the
 * next heartbeat.
 *
 * Fail-safe by construction. Nothing here is required: every value carries the default the
 * app shipped with, a field that is missing or out of range keeps that default, a regex
 * that does not compile is skipped, and a config that cannot be read at all simply leaves
 * [DEFAULTS] in force. No server edit can make the app throw or go quiet.
 *
 * Pure Kotlin, no Android imports, so the parsing is unit-tested on the JVM.
 */
data class MobileConfig(
    /** 0 means "no server config has ever been applied". */
    val version: Int = 0,
    val lookbackHours: Int = DEFAULT_LOOKBACK_HOURS,
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
    val heartbeatIntervalMinutes: Int = DEFAULT_HEARTBEAT_INTERVAL_MINUTES,
    val ringAllowanceSec: Int = DEFAULT_RING_ALLOWANCE_SEC,
    val maxDurationSlackSec: Int = DEFAULT_MAX_DURATION_SLACK_SEC,
    val unmatchedRetentionDays: Int = DEFAULT_UNMATCHED_RETENTION_DAYS,
    val byPhoneRecheckHours: Int = DEFAULT_BY_PHONE_RECHECK_HOURS,
    val extraFolders: List<String> = emptyList(),
    val recorderProfiles: List<RecorderProfile> = emptyList(),
) {

    /** One OEM's recorder, as the server describes it. */
    data class RecorderProfile(
        val manufacturerMatch: Pattern,
        /** "from_answer" (audio starts when answered) or "from_dial" (ringing is recorded). */
        val durationSemantics: String,
        val extensions: List<String>,
        val folders: List<String>,
    ) {
        fun matches(manufacturer: String?): Boolean =
            !manufacturer.isNullOrBlank() && manufacturerMatch.matcher(manufacturer).find()
    }

    val lookbackMillis: Long get() = lookbackHours * HOUR_MILLIS
    val unmatchedRetentionMillis: Long get() = unmatchedRetentionDays * DAY_MILLIS
    val byPhoneRecheckMillis: Long get() = byPhoneRecheckHours * HOUR_MILLIS

    /** The first profile whose pattern hits this manufacturer, or null for an unknown phone. */
    fun profileFor(manufacturer: String?): RecorderProfile? =
        recorderProfiles.firstOrNull { it.matches(manufacturer) }

    /** Profile folders plus the org's extra folders, in config order, de-duplicated. */
    fun foldersFor(manufacturer: String?): List<String> =
        (profileFor(manufacturer)?.folders.orEmpty() + extraFolders)
            .map { it.trim().trim('/') }
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * A recorder that starts at *answer* cannot legitimately produce audio longer than the
     * talk time by a ring, so the allowance is only granted to record-from-dial profiles —
     * and to phones the config does not know, where the shipped behaviour is kept.
     */
    fun ringAllowanceSecFor(manufacturer: String?): Int =
        if (profileFor(manufacturer)?.durationSemantics == FROM_ANSWER) 0 else ringAllowanceSec

    companion object {
        const val DEFAULT_LOOKBACK_HOURS = 72
        const val DEFAULT_SYNC_INTERVAL_MINUTES = 15
        const val DEFAULT_HEARTBEAT_INTERVAL_MINUTES = 60
        const val DEFAULT_RING_ALLOWANCE_SEC = 35
        const val DEFAULT_MAX_DURATION_SLACK_SEC = 20
        const val DEFAULT_UNMATCHED_RETENTION_DAYS = 7
        const val DEFAULT_BY_PHONE_RECHECK_HOURS = 6

        const val FROM_ANSWER = "from_answer"
        const val FROM_DIAL = "from_dial"

        private const val HOUR_MILLIS = 60L * 60 * 1000
        private const val DAY_MILLIS = 24 * HOUR_MILLIS

        val DEFAULTS = MobileConfig()

        /**
         * Typed values from a decoded response. Ranges mirror the backend's own validation,
         * so an operator cannot set, say, a one-minute heartbeat by editing the JSON by hand.
         */
        fun from(response: MobileConfigResponseDto): MobileConfig {
            val cfg = response.config ?: MobileConfigDto()
            return MobileConfig(
                version = response.version.coerceAtLeast(0),
                lookbackHours = cfg.lookbackHours.orDefault(DEFAULT_LOOKBACK_HOURS, 1, 720),
                syncIntervalMinutes = cfg.syncIntervalMinutes.orDefault(DEFAULT_SYNC_INTERVAL_MINUTES, 1, 1440),
                heartbeatIntervalMinutes =
                    cfg.heartbeatIntervalMinutes.orDefault(DEFAULT_HEARTBEAT_INTERVAL_MINUTES, 5, 1440),
                ringAllowanceSec = cfg.ringAllowanceSec.orDefault(DEFAULT_RING_ALLOWANCE_SEC, 0, 300),
                maxDurationSlackSec = cfg.maxDurationSlackSec.orDefault(DEFAULT_MAX_DURATION_SLACK_SEC, 0, 600),
                unmatchedRetentionDays =
                    cfg.unmatchedRetentionDays.orDefault(DEFAULT_UNMATCHED_RETENTION_DAYS, 1, 90),
                byPhoneRecheckHours = cfg.byPhoneRecheckHours.orDefault(DEFAULT_BY_PHONE_RECHECK_HOURS, 1, 168),
                extraFolders = cfg.extraFolders.cleanStrings(),
                recorderProfiles = cfg.recorderProfiles.orEmpty().mapNotNull(::profileFrom),
            )
        }

        /** A profile with no usable manufacturer pattern is skipped, never fatal. */
        private fun profileFrom(dto: RecorderProfileDto): RecorderProfile? {
            val source = dto.manufacturerMatch?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val pattern = runCatching { Pattern.compile(source, Pattern.CASE_INSENSITIVE) }
                .getOrElse { if (it is PatternSyntaxException || it is IllegalArgumentException) null else throw it }
                ?: return null
            return RecorderProfile(
                manufacturerMatch = pattern,
                durationSemantics = dto.durationSemantics?.takeIf { it == FROM_ANSWER || it == FROM_DIAL }
                    ?: FROM_DIAL,
                extensions = dto.extensions.cleanStrings().map { it.lowercase().removePrefix(".") },
                folders = dto.folders.cleanStrings(),
            )
        }

        private fun Int?.orDefault(default: Int, min: Int, max: Int): Int =
            this?.coerceIn(min, max) ?: default

        private fun List<String>?.cleanStrings(): List<String> =
            orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
