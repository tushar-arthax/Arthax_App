package ai.arthax.app.push

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import ai.arthax.app.call.CallTracker
import ai.arthax.app.data.local.prefs.AppSettings
import ai.arthax.app.data.repository.EventLogger
import ai.arthax.app.domain.model.CallMode
import ai.arthax.app.domain.model.Lead
import ai.arthax.app.domain.model.LogStage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Places a call that was asked for by a notification or the in-app confirm sheet.
 *
 * The same path as the CALL button — [CallTracker.beginCall] — so the call is remembered
 * and attributed the same way, only with the source the request carried. The one extra is
 * the permission fallback: the phone may have lost CALL_PHONE since setup, and a request
 * from a colleague must still end with the dialler open rather than a silent nothing.
 */
@Singleton
class DialRequestPlacer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callTracker: CallTracker,
    private val settings: AppSettings,
    private val logger: EventLogger,
) {

    /** True when a dial or call intent was fired. */
    suspend fun place(request: DialRequest): Boolean {
        val lead = Lead(id = request.leadId, name = request.leadName, phoneNumber = request.phone)

        var mode = settings.snapshot.first().callMode
        if (mode == CallMode.DIRECT && !canPlaceCalls()) {
            // Not an error: the dialler opens with the number filled in, one tap short.
            logger.warn(
                LogStage.CALL,
                "Phone permission is missing — opening the dialler instead of dialling",
                leadId = lead.id,
                leadName = lead.name,
            )
            mode = CallMode.DIALER
        }

        val launch = callTracker.beginCall(lead, mode, request.source)
        return try {
            context.startActivity(launch.intent)
            true
        } catch (e: SecurityException) {
            // The permission check above can race a revoke; fall back rather than give up.
            fallbackToDialler(lead, e)
        } catch (e: ActivityNotFoundException) {
            callTracker.abandonCall("No phone app could handle the call")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            callTracker.abandonCall("${e::class.java.simpleName}: ${e.message.orEmpty()}")
            false
        }
    }

    private suspend fun fallbackToDialler(lead: Lead, cause: Exception): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", lead.phoneNumber, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        callTracker.abandonCall("Permission to place calls was denied (${cause.message.orEmpty()})")
        false
    }

    private fun canPlaceCalls(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
}
