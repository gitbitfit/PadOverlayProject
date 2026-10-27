package com.example.padoverlay

import android.content.ComponentName
import android.content.Context
import rikka.shizuku.Shizuku

/** One place for the Shizuku user-service settings (shared by the overlay and the main screen). */
object PadService {
    const val VERSION = 11

    fun args(ctx: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(ctx.packageName, InputService::class.java.name))
            .processNameSuffix("input")
            .version(VERSION)
}

/** Game window shapes for window mode. */
object Win {
    val LABELS = arrayOf("16:9", "16:10", "4:3", "3:2")
    private val RATIOS = floatArrayOf(16f / 9f, 16f / 10f, 4f / 3f, 3f / 2f)

    fun ratio(index: Int): Float = RATIOS[index.coerceIn(0, RATIOS.size - 1)]

    /** [left, top, right, bottom] of the centred game window on a screen of sw x sh pixels. */
    fun bounds(sw: Int, sh: Int, ratio: Float): IntArray {
        val w: Int
        val h: Int
        if (sw >= sh) {
            w = minOf(sw.toFloat(), sh * ratio).toInt()
            h = (w / ratio).toInt()
        } else {
            h = minOf(sh.toFloat(), sw * ratio).toInt()
            w = (h / ratio).toInt()
        }
        val l = (sw - w) / 2
        val t = (sh - h) / 2
        return intArrayOf(l, t, l + w, t + h)
    }
}
