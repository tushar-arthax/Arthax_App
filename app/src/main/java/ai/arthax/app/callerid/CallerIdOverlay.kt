package ai.arthax.app.callerid

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ai.arthax.app.MainActivity
import ai.arthax.app.R
import ai.arthax.app.ui.navigation.MainTab
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The floating lead card shown over the phone's incoming-call screen.
 *
 * Plain views rather than Compose, on purpose. A Compose root inside a WindowManager
 * overlay needs a hand-made lifecycle, saved-state and view-model owner attached to the
 * window, and getting any of that subtly wrong is a crash in a code path that runs while
 * the phone is ringing and the app may be dead. A handful of widgets built in code have no
 * such requirements and no layout inflation to go wrong.
 *
 * Styled like the app: the brand's near-black ground, white name, muted detail line. It
 * never takes focus (the rep must still be able to answer with the phone's own buttons),
 * can be dragged out of the way, and is dismissed by the presenter the moment the line
 * stops ringing, with a timeout as a backstop.
 */
@Singleton
class CallerIdOverlay @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Card(
        val leadId: String,
        val name: String,
        val detail: String?,
        val footer: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    private val timeout = Runnable { dismiss() }

    fun isShowing(): Boolean = view != null

    /** Main thread only. Returns false when the window could not be added. */
    fun show(card: Card): Boolean {
        if (!CallerIdSetup.canDrawOverlays(context)) return false
        val windows = context.getSystemService(WindowManager::class.java) ?: return false

        dismiss()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(96)
        }

        val root = buildCard(card, params, windows)

        return runCatching {
            windows.addView(root, params)
            view = root
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, TIMEOUT_MILLIS)
            true
        }.onFailure { Log.w(TAG, "Could not show the caller-ID card", it) }
            .getOrDefault(false)
    }

    /** Main thread only. Safe to call when nothing is showing. */
    fun dismiss() {
        handler.removeCallbacks(timeout)
        val current = view ?: return
        view = null
        val windows = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { windows.removeViewImmediate(current) }
            .onFailure { Log.w(TAG, "Could not remove the caller-ID card", it) }
    }

    private fun buildCard(
        card: Card,
        params: WindowManager.LayoutParams,
        windows: WindowManager,
    ): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(BACKGROUND)
                setStroke(dp(1), BORDER)
            }
            elevation = dp(8).toFloat()
        }

        // Horizontal margin, since the window itself is full width.
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(
                outer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val mark = ImageView(context).apply {
            setImageResource(R.drawable.arthax_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        outer.addView(mark, LinearLayout.LayoutParams(dp(44), dp(44)))

        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(8), 0)
        }
        outer.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        text.addView(
            TextView(context).apply {
                this.text = card.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        card.detail?.takeIf { it.isNotBlank() }?.let { detail ->
            text.addView(
                TextView(context).apply {
                    this.text = detail
                    setTextColor(DETAIL)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
        }
        text.addView(
            TextView(context).apply {
                this.text = card.footer
                setTextColor(ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
            },
        )

        val close = TextView(context).apply {
            this.text = "✕"
            setTextColor(DETAIL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            contentDescription = "Dismiss"
            setOnClickListener { dismiss() }
        }
        outer.addView(close, LinearLayout.LayoutParams(dp(36), dp(36)))

        // Tap opens the lead; drag moves the card out of the way of the answer button.
        outer.setOnTouchListener(DragOrTap(params, windows, wrapper) { openLead(card.leadId) })

        return wrapper
    }

    private fun openLead(leadId: String) {
        runCatching {
            context.startActivity(MainActivity.openIntent(context, MainTab.LEADS, leadId))
        }
        dismiss()
    }

    private inner class DragOrTap(
        private val params: WindowManager.LayoutParams,
        private val windows: WindowManager,
        private val root: View,
        private val onTap: () -> Unit,
    ) : View.OnTouchListener {
        private var startY = 0
        private var downRawY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = params.y
                    downRawY = event.rawY
                    moved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(delta) > dp(6)) moved = true
                    if (moved) {
                        params.y = (startY + delta).coerceAtLeast(0)
                        runCatching { windows.updateViewLayout(root, params) }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                        onTap()
                    }
                }
            }
            return true
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TAG = "CallerIdOverlay"

        /** The card never outlives a ring: 90 seconds is past any carrier's ring timeout. */
        const val TIMEOUT_MILLIS = 90_000L

        val BACKGROUND = Color.parseColor("#F20F0F12")
        val BORDER = Color.parseColor("#33FFFFFF")
        val DETAIL = Color.parseColor("#B3FFFFFF")
        // `--neon`, the dark theme's primary (ui/theme/Color.kt), so the card reads as Arthax.
        val ACCENT = Color.parseColor("#FF00FF55")
    }
}
