package ai.arthax.app.callerid

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The two grants that make caller ID work like Truecaller's, and how to ask for each.
 *
 * Neither is a runtime permission; both are system screens the rep has to say yes on:
 *
 *  - **Call screening role** (Android 10+). Makes Android hand every incoming call to
 *    [CallerIdScreeningService] *with* the number, before the phone rings. Without it the
 *    app falls back to the PHONE_STATE broadcast, which still carries the number because
 *    READ_CALL_LOG is granted, but which Xiaomi and Oppo builds do not deliver reliably.
 *
 *  - **Draw over other apps**. The lead card is a floating window shown on top of the
 *    phone's own incoming-call screen. Without it the same information is posted as a
 *    heads-up notification instead, which most phones still show over the call screen.
 *
 * Caller ID is usable with neither granted, and better with each one.
 */
object CallerIdSetup {

    fun isScreeningRoleSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isScreeningRoleHeld(context: Context): Boolean {
        if (!isScreeningRoleSupported()) return false
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching {
            roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
                roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        }.getOrDefault(false)
    }

    /** Null when the role does not exist on this device. */
    fun screeningRoleIntent(context: Context): Intent? {
        if (!isScreeningRoleSupported()) return null
        val roles = context.getSystemService(RoleManager::class.java) ?: return null
        return runCatching {
            if (roles.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
            } else {
                null
            }
        }.getOrNull()
    }

    fun canDrawOverlays(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

    fun overlayPermissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /** A one-line summary for the settings screen. */
    fun status(context: Context): Status = Status(
        screeningRoleSupported = isScreeningRoleSupported(),
        screeningRoleHeld = isScreeningRoleHeld(context),
        overlayAllowed = canDrawOverlays(context),
    )

    data class Status(
        val screeningRoleSupported: Boolean,
        val screeningRoleHeld: Boolean,
        val overlayAllowed: Boolean,
    ) {
        /** Everything this device can grant has been granted. */
        val isComplete: Boolean
            get() = overlayAllowed && (!screeningRoleSupported || screeningRoleHeld)
    }
}
