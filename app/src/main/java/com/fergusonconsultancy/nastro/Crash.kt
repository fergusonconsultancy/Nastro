package com.fergusonconsultancy.nastro

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * If Nastro closes on a bug, what went wrong is written down before it goes, and shown the next
 * time it opens, ready to copy or send. Without it a crash on the phone leaves nothing to fix from.
 */
object Crash {
    private const val FILE = "last-crash.txt"
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val trace = android.util.Log.getStackTraceString(e)
                val head = "Nastro ${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()} · " +
                    "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL} · thread ${t.name}\n\n"
                File(app.filesDir, FILE).writeText(head + trace)
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Show the last crash, if there was one, then forget it. */
    fun report(a: Activity) {
        val f = File(a.filesDir, FILE)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        if (text.isBlank()) return
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Nastro closed last time")
            .setMessage("This is what went wrong. Copy it or send it, and paste it to Claude.\n\n" + text.take(2500))
            .setPositiveButton("Copy") { _, _ ->
                a.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Nastro crash", text))
            }
            .setNeutralButton("Send") { _, _ ->
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Send the crash report"))
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }
}
