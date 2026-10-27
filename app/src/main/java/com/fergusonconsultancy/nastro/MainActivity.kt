package com.fergusonconsultancy.nastro

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowManager
import android.window.OnBackInvokedDispatcher

class MainActivity : Activity() {
    private lateinit var stage: StageView
    private lateinit var sheet: SettingsSheet

    private val audioPermission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Deck.init(this)
        // Full-bleed: the player's metal runs under the status and navigation bars
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        window.setDecorFitsSystemWindows(false)

        stage = StageView(this)
        stage.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        stage.onAskPermission = { requestPermissions(arrayOf(audioPermission), 1) }
        // Settings that change the shelf rebuild it; the tape in the deck stays where it is
        sheet = SettingsSheet(this) {
            Library.reload(this) { Deck.relink(); stage.libraryChanged() }
        }
        stage.onOpenSettings = { sheet.show() }
        sheet.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        val root = android.widget.FrameLayout(this)
        root.addView(stage)
        root.addView(sheet)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (sheet.showing) sheet.hide() else if (!stage.back()) finish()
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Asked alongside music access on first run, so the tape can carry on with the screen off
        }
        start()
    }

    @Deprecated("Pre-33 back handling")
    override fun onBackPressed() {
        if (sheet.showing) sheet.hide() else if (!stage.back()) @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun start() {
        if (checkSelfPermission(audioPermission) != PackageManager.PERMISSION_GRANTED) {
            stage.needsPermission = true; stage.invalidate(); return
        }
        stage.needsPermission = false
        if (Library.loaded) { Deck.restore(); stage.libraryChanged(); return }
        Library.load(this) {
            Deck.restore()
            stage.libraryChanged()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            start()
        }
    }

    override fun onResume() { super.onResume(); stage.invalidate() }
    override fun onPause() { Deck.save(); super.onPause() }
}
