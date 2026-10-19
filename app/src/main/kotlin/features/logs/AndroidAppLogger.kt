// Copyright 2026, AsteriskMETA contributors
// SPDX-License-Identifier: GPL-3.0

package features.logs

import android.util.Log
import engine.mihomo.tailscale.redactTailnetSecrets

internal object AndroidAppLogger {
    fun platformWarn(tag: String, message: String, error: Throwable? = null) {
        if (error == null) {
            Log.w(tag, redactTailnetSecrets(message))
        } else {
            Log.w(tag, redactedLogText(message, error))
        }
    }

    fun debug(tag: String, message: String, error: Throwable? = null) {
        val timestampMillis = System.currentTimeMillis()
        if (error == null) {
            Log.d(tag, redactTailnetSecrets(message))
        } else {
            Log.d(tag, redactedLogText(message, error))
        }
        AndroidLogcatRepository.append("debug", logcatMessage(tag, message, error), timestampMillis)
    }

    fun info(tag: String, message: String, error: Throwable? = null) {
        val timestampMillis = System.currentTimeMillis()
        if (error == null) {
            Log.i(tag, redactTailnetSecrets(message))
        } else {
            Log.i(tag, redactedLogText(message, error))
        }
        AndroidLogcatRepository.append("info", logcatMessage(tag, message, error), timestampMillis)
    }

    fun warn(tag: String, message: String, error: Throwable? = null) {
        val timestampMillis = System.currentTimeMillis()
        if (error == null) {
            Log.w(tag, redactTailnetSecrets(message))
        } else {
            Log.w(tag, redactedLogText(message, error))
        }
        AndroidLogcatRepository.append("warning", logcatMessage(tag, message, error), timestampMillis)
    }

    fun error(tag: String, message: String, error: Throwable? = null) {
        val timestampMillis = System.currentTimeMillis()
        if (error == null) {
            Log.e(tag, redactTailnetSecrets(message))
        } else {
            Log.e(tag, redactedLogText(message, error))
        }
        AndroidLogcatRepository.append("error", logcatMessage(tag, message, error), timestampMillis)
    }

    private fun logcatMessage(tag: String, message: String, error: Throwable?): String {
        return buildString {
            append(tag)
            append(": ")
            append(redactTailnetSecrets(message))
            if (error != null) {
                val stackTrace = redactTailnetSecrets(Log.getStackTraceString(error).trim())
                if (stackTrace.isNotEmpty()) {
                    append('\n')
                    append(stackTrace)
                }
            }
        }
    }

    /** Platform log text with the stack trace inlined, so exception messages are redacted too. */
    private fun redactedLogText(message: String, error: Throwable): String =
        redactTailnetSecrets("$message\n${Log.getStackTraceString(error)}")
}
