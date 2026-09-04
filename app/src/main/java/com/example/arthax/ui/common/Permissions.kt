package com.example.arthax.ui.common

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.app.Activity
import android.content.ContextWrapper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime permission plumbing, hand-rolled rather than pulled from Accompanist — that
 * library folded its permissions API into androidx and the shim adds a dependency for
 * what amounts to three functions.
 */
object AppPermissions {

    data class Requirement(
        val permission: String,
        val title: String,
        val rationale: String,
        val required: Boolean,
    )

    /** Everything the capture pipeline needs, in the order the setup screen asks for it. */
    fun requirements(): List<Requirement> = buildList {
        add(
            Requirement(
                permission = Manifest.permission.CALL_PHONE,
                title = "Place calls",
                rationale = "Lets the CALL button dial a lead directly from the list.",
                required = true,
            ),
        )
        add(
            Requirement(
                permission = Manifest.permission.READ_PHONE_STATE,
                title = "Detect call start and end",
                rationale = "Tells the app when a call connects and when it finishes, " +
                    "so the recording can be matched to the right lead.",
                required = true,
            ),
        )
        add(
            Requirement(
                permission = Manifest.permission.READ_CALL_LOG,
                title = "Read call outcome and duration",
                rationale = "Android cannot tell the app whether someone answered. The " +
                    "phone's own call log can, so your CRM shows real talk time and marks " +
                    "unanswered calls correctly.",
                required = true,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                Requirement(
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    title = "Show upload status",
                    rationale = "Used to show call tracking progress and to warn you if a " +
                        "recording could not be uploaded.",
                    required = false,
                ),
            )
        }
    }

    fun all(): List<String> = requirements().map { it.permission }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context): List<String> = all().filterNot { isGranted(context, it) }

    /** Only the permissions without which the app genuinely cannot do its job. */
    fun missingRequired(context: Context): List<String> =
        requirements().filter { it.required && !isGranted(context, it.permission) }
            .map { it.permission }

    /**
     * True when a permission looks impossible to grant from inside the app.
     *
     * Two very different situations produce the same signals — denied, and the system says
     * not to show a rationale:
     *
     *  - the rep ticked "don't ask again", which app settings can fix; or
     *  - the permission is *hard restricted* and the installer never granted an exemption,
     *    which app settings cannot fix at all.
     *
     * READ_CALL_LOG is hard restricted from Android 10. Verified on device: an APK installed
     * without the exemption shows APPLY_RESTRICTION, and the permission stays denied even
     * when granted explicitly by shell — silently, with no dialog and no error. Detecting
     * this matters, because otherwise a rep taps Grant forever and nothing ever happens.
     */
    fun looksUngrantable(context: Context, permission: String, alreadyRequested: Boolean): Boolean {
        if (!alreadyRequested) return false
        if (isGranted(context, permission)) return false

        val activity = context.findActivity() ?: return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
