package com.example.arthax.core

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records an uncaught crash to disk so the next launch can show what happened.
 *
 * A rep in the field cannot read logcat. Without this, a crash is "the app closed and Xiaomi
 * offered to report it to MIUI" — which says nothing about the cause, and cost days of
 * guesswork on a bug that a single stack trace would have identified immediately.
 *
 * The write is deliberately plain and synchronous: the process is already dying, so there is
 * no time for coroutines, and no dependency worth risking. Whatever handler was installed
 * before is still called afterwards, so the system's own reporting is unaffected.
 */
@Singleton
class CrashRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(thread, throwable) }
            // Always hand back to the platform: swallowing this would leave the process in a
            // broken half-alive state rather than dying cleanly.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()

        val report = buildString {
            appendLine("time: ${TIMESTAMP.format(Date())}")
            appendLine("thread: ${thread.name}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(stack.take(MAX_STACK_CHARS))
        }

        file.writeText(report)
        Log.e(TAG, "Crash recorded to ${file.name}")
    }

    /**
     * The crash from the previous run, if there was one. Consumed as it is read, so the same
     * crash is reported once and does not haunt every subsequent launch.
     */
    fun consumePreviousCrash(): String? = runCatching {
        if (!file.exists()) return null
        val report = file.readText().takeIf { it.isNotBlank() }
        file.delete()
        report
    }.getOrNull()

    private companion object {
        const val TAG = "ArthaxCrash"
        const val FILE_NAME = "arthax_last_crash.txt"
        const val MAX_STACK_CHARS = 4_000

        val TIMESTAMP: SimpleDateFormat
            get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
