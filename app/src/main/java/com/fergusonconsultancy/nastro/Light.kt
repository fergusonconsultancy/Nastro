package com.fergusonconsultancy.nastro

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import kotlin.math.abs
import kotlin.math.exp

/**
 * Where the light falls from (14 Nov 2026). Mechack: the metal is lovely but reads flat, with
 * little emotion. Real anodised metal is never still in the hand: tilt it and the highlight
 * slides across the brushing. So the phone's gravity sensor moves the light. Only the change
 * counts: the rest position follows slowly, so however the phone is held the light sits in its
 * usual place, upper left, and moves as the phone moves.
 *
 * [x], [y] run -1..1 in screen terms: positive x moves the highlight right, positive y down.
 */
object Light : SensorEventListener {
    var x = 0f; private set
    var y = 0f; private set
    /** The display's rotation (Surface.ROTATION_*), so the sensor's axes turn with the screen. */
    var rotation = Surface.ROTATION_0

    private var sm: SensorManager? = null
    private var onChange: (() -> Unit)? = null
    private var restX = Float.NaN; private var restY = Float.NaN
    private var last = 0L
    private var shownX = 0f; private var shownY = 0f

    fun start(ctx: Context, changed: () -> Unit) {
        onChange = changed
        val m = ctx.getSystemService(SensorManager::class.java) ?: return
        val sensor = m.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm = m
        m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sm?.unregisterListener(this); sm = null; onChange = null
        restX = Float.NaN; restY = Float.NaN
    }

    override fun onSensorChanged(e: SensorEvent) {
        // gravity along the device's own axes, in g, turned to the screen's
        val ax = e.values[0] / SensorManager.GRAVITY_EARTH
        val ay = e.values[1] / SensorManager.GRAVITY_EARTH
        val (sx, sy) = when (rotation) {
            Surface.ROTATION_90 -> -ay to ax
            Surface.ROTATION_180 -> -ax to -ay
            Surface.ROTATION_270 -> ay to -ax
            else -> ax to ay
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last) / 1000f).coerceIn(0f, 0.2f)
        last = now
        if (restX.isNaN()) { restX = sx; restY = sy }
        // the rest position drifts after the hand over a few seconds
        val a = 1 - exp(-dt / 3.5f)
        restX += (sx - restX) * a; restY += (sy - restY) * a
        // tilting the top away (sy falls) lifts the highlight; tilting right moves it left, as a fixed lamp would
        val tx = ((restX - sx) * 2.4f).coerceIn(-1f, 1f)
        val ty = ((restY - sy) * -2.4f).coerceIn(-1f, 1f)
        val b = 1 - exp(-dt / 0.08f)
        x += (tx - x) * b; y += (ty - y) * b
        if (abs(x - shownX) > 0.008f || abs(y - shownY) > 0.008f) { shownX = x; shownY = y; onChange?.invoke() }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
