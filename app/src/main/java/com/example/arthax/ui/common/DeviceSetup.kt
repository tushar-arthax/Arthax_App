package com.example.arthax.ui.common

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.example.arthax.core.MobileConfig

/**
 * OEM power-management escape hatches.
 *
 * This is the unglamorous half of making background call capture actually work. Stock
 * Android honours a foreground service; MIUI, ColorOS and Funtouch add their own kill
 * layers on top that no amount of correct API usage can satisfy. The rep has to flip
 * these switches by hand, so the least we can do is take them straight to the screen.
 */
object DeviceSetup {

    fun isBatteryOptimised(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the standard battery-optimisation exemption dialog.
     *
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS shows a single confirm dialog. Play
     * allows it for apps whose core function breaks without it, provided the request is
     * explained first — which is what the "Keep Arthax running" step does before this is
     * ever launched. We still fall back to the general list if the OEM removed the dialog.
     */
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun batterySettingsFallbackIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** True when this handset is from a manufacturer known to need a manual autostart grant. */
    fun needsAutostartGrant(): Boolean =
        Build.MANUFACTURER.lowercase() in MANUFACTURERS_WITH_AUTOSTART

    fun manufacturerLabel(): String =
        Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    /**
     * Best-effort deep link into the OEM autostart screen. These are undocumented
     * components that move between firmware versions, so every one is tried in turn and
     * the caller falls back to written instructions if none resolve.
     */
    fun autostartIntents(): List<Intent> = AUTOSTART_COMPONENTS.map { (pkg, cls) ->
        Intent().setComponent(ComponentName(pkg, cls))
    }

    fun firstResolvable(context: Context, intents: List<Intent>): Intent? =
        intents.firstOrNull { intent ->
            context.packageManager.resolveActivity(intent, 0) != null
        }

    /** Written fallback when no autostart screen could be opened automatically. */
    fun autostartInstructions(): String = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" ->
            "Open Settings > Apps > Manage apps > Arthax, then turn on Autostart and set " +
                "Battery saver to No restrictions."
        "oppo", "realme", "oneplus" ->
            "Open Settings > Battery > App battery management > Arthax, then allow " +
                "background running and turn off any auto-optimisation."
        "vivo", "iqoo" ->
            "Open Settings > Battery > Background power consumption management > Arthax " +
                "and allow high background power use."
        "huawei", "honor" ->
            "Open Settings > Apps > Arthax > Battery, set Launch to Manage manually and " +
                "enable all three switches."
        "samsung" ->
            "Open Settings > Battery > Background usage limits and make sure Arthax is not " +
                "in the Sleeping or Deep sleeping apps list."
        else ->
            "Open your phone Settings > Battery and allow Arthax to run in the background."
    }

    private val MANUFACTURERS_WITH_AUTOSTART = setOf(
        "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo", "huawei", "honor",
    )

    private val AUTOSTART_COMPONENTS = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
    )
}

/**
 * Suggested starting folders for the recordings picker, by manufacturer. Passed to the
 * picker as EXTRA_INITIAL_URI so the rep lands near the right place instead of hunting
 * through the whole filesystem.
 *
 * The server config comes first: its recorder profiles are maintained against the fleet
 * as new phone models turn up, so a Vivo build that moved its folder is fixed by an edit
 * in the CRM rather than a new APK. The built-in list is what the app shipped with, and
 * what an unknown phone — or a phone that has never reached the server — falls back to.
 */
object RecordingFolderHints {

    fun likelyPathsFor(config: MobileConfig = MobileConfig.DEFAULTS): List<String> {
        val fromServer = config.foldersFor(Build.MANUFACTURER)
        if (fromServer.isNotEmpty()) return fromServer
        return builtIn()
    }

    private fun builtIn(): List<String> = when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> listOf("Recordings/Call", "Recordings", "Sounds")
        "xiaomi", "redmi", "poco" -> listOf("MIUI/sound_recorder/call_rec", "MIUI/sound_recorder", "Recorder")
        "oppo", "realme", "oneplus" -> listOf("Recordings/Call Recordings", "Music/Recordings", "Recordings")
        "vivo", "iqoo" -> listOf("Record/Call", "Recordings")
        "huawei", "honor" -> listOf("Sounds/CallRecord", "Sounds")
        else -> listOf("Recordings", "Call", "Sounds")
    }

    /**
     * A best-guess initial URI for the system picker. Purely a convenience — the rep
     * still confirms the folder, and we only ever use what they actually pick.
     */
    fun initialTreeUri(config: MobileConfig = MobileConfig.DEFAULTS): Uri? = runCatching {
        val path = likelyPathsFor(config).first().replace("/", "%2F")
        Uri.parse("content://com.android.externalstorage.documents/document/primary%3A$path")
    }.getOrNull()

    fun humanHint(config: MobileConfig = MobileConfig.DEFAULTS): String {
        val paths = likelyPathsFor(config).joinToString(", ") { "Internal storage / $it" }
        return "On ${DeviceSetup.manufacturerLabel()} phones this is usually: $paths"
    }
}
