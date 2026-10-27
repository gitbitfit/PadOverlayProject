package com.example.padoverlay

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.FileWriter
import java.io.Writer
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Identity and layout of the virtual controller. */
private class HidSpec(val name: String, val vid: Int, val pid: Int, val xbox: Boolean)

/**
 * Runs in a Shizuku-started process with shell privileges.
 *
 * Gamepad modes: creates a REAL virtual HID gamepad using Android's built-in `hid`
 * shell command (Generic, Xbox One S identity, or DualShock-style name).
 * Touch mode: no gamepad is created; touches are injected instead.
 *
 * Log file: /sdcard/Download/padoverlay-log.txt
 */
class InputService : Binder() {

    companion object {
        const val CODE_KEY = 1
        const val CODE_STICK = 2
        const val CODE_TOUCH = 3
        const val CODE_PROFILE = 4
        const val CODE_LAUNCH = 5
        const val CODE_DESTROY = 16777114 // required by Shizuku

        const val PROFILE_GENERIC = 0
        const val PROFILE_XBOX = 1
        const val PROFILE_DS4 = 2
        const val PROFILE_DS5 = 3
        const val PROFILE_NONE = 9

        private const val LOG_FILE = "/sdcard/Download/padoverlay-log.txt"

        private val SPECS = mapOf(
            PROFILE_GENERIC to HidSpec("PadOverlay Gamepad", 4617, 1, false),   // 1209:0001
            PROFILE_XBOX to HidSpec("Xbox Wireless Controller", 1118, 736, true), // 045e:02e0
            PROFILE_DS4 to HidSpec("Wireless Controller", 4617, 2, false),      // name only
            PROFILE_DS5 to HidSpec("DualSense Wireless Controller", 4617, 3, false) // name only
        )

        // Report: 15 buttons + 1 pad bit | hat(4) + pad(4) | X Y Z Rz | Brake(LT) Gas(RT) | [Xbox: Back + 7 pad]
        private val BASE_DESCRIPTOR = intArrayOf(
            0x05, 0x01, 0x09, 0x05, 0xA1, 0x01,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x0F, 0x15, 0x00, 0x25, 0x01,
            0x75, 0x01, 0x95, 0x0F, 0x81, 0x02,
            0x95, 0x01, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00,
            0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
            0x65, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
            0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
            0x05, 0x02, 0x09, 0xC5, 0x09, 0xC4,
            0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02
        )

        // Consumer "AC Back": what a real Xbox controller's View button sends
        private val BACK_BLOCK = intArrayOf(
            0x05, 0x0C, 0x0A, 0x24, 0x02,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02,
            0x75, 0x07, 0x95, 0x01, 0x81, 0x03
        )

        // Android key code -> bit in the HID button field (HID button number - 1)
        private val BUTTON_BITS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to 0,
            KeyEvent.KEYCODE_BUTTON_B to 1,
            KeyEvent.KEYCODE_BUTTON_X to 3,
            KeyEvent.KEYCODE_BUTTON_Y to 4,
            KeyEvent.KEYCODE_BUTTON_L1 to 6,
            KeyEvent.KEYCODE_BUTTON_R1 to 7,
            KeyEvent.KEYCODE_BUTTON_L2 to 8,
            KeyEvent.KEYCODE_BUTTON_R2 to 9,
            KeyEvent.KEYCODE_BUTTON_SELECT to 10,
            KeyEvent.KEYCODE_BUTTON_START to 11,
            KeyEvent.KEYCODE_BUTTON_MODE to 12,
            KeyEvent.KEYCODE_BUTTON_THUMBL to 13,
            KeyEvent.KEYCODE_BUTTON_THUMBR to 14,
        )

        // Same, for the Xbox profile (Menu = button 13, View = consumer Back, no digital L2/R2)
        private val XBOX_BITS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to 0,
            KeyEvent.KEYCODE_BUTTON_B to 1,
            KeyEvent.KEYCODE_BUTTON_X to 3,
            KeyEvent.KEYCODE_BUTTON_Y to 4,
            KeyEvent.KEYCODE_BUTTON_L1 to 6,
            KeyEvent.KEYCODE_BUTTON_R1 to 7,
            KeyEvent.KEYCODE_BUTTON_START to 12,
            KeyEvent.KEYCODE_BUTTON_THUMBL to 13,
            KeyEvent.KEYCODE_BUTTON_THUMBR to 14,
        )
    }

    private val lock = Any()

    // Keep every button down for a minimum time so games that poll slowly
    // (menu / map toggles) never miss a quick tap.
    private val minHoldMs = 70L
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val downAt = HashMap<Int, Long>()
    private val pendingUp = HashMap<Int, ScheduledFuture<*>>()

    // HID gamepad state
    private var spec: HidSpec = SPECS.getValue(PROFILE_GENERIC)
    private var hidProcess: Process? = null
    private var hidOut: Writer? = null
    private var buttons = 0
    private var dUp = false
    private var dDown = false
    private var dLeft = false
    private var dRight = false
    private var lTrig = false
    private var rTrig = false
    private var back = false
    private val axes = FloatArray(4) // X, Y, Z, RZ

    // Event injection (touch, and fallback for keys)
    private var inputManager: Any? = null
    private var inject: Method? = null

    init {
        log("InputService starting, uid=${android.os.Process.myUid()}, sdk=${Build.VERSION.SDK_INT}")
        initInjection()
    }

    // ------------------------------------------------------------------ binder

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            CODE_KEY -> {
                val keyCode = data.readInt()
                val down = data.readInt() != 0
                key(keyCode, down)
                return true
            }
            CODE_STICK -> {
                val id = data.readInt()
                val x = data.readFloat()
                val y = data.readFloat()
                stick(id, x, y)
                return true
            }
            CODE_TOUCH -> {
                val slot = data.readInt()
                val action = data.readInt()
                val x = data.readFloat()
                val y = data.readFloat()
                touch(slot, action, x, y)
                return true
            }
            CODE_PROFILE -> {
                applyProfile(data.readInt())
                return true
            }
            CODE_LAUNCH -> {
                val component = data.readString() ?: ""
                val pkg = data.readString() ?: ""
                val l = data.readInt()
                val t = data.readInt()
                val r = data.readInt()
                val b = data.readInt()
                var status: String
                var rect: IntArray? = null
                try {
                    val res = launchWindowed(component, pkg, l, t, r, b)
                    status = res.first
                    rect = res.second
                } catch (e: Throwable) {
                    status = "Error: $e"
                }
                reply?.writeString(status)
                reply?.writeInt(if (rect != null) 1 else 0)
                if (rect != null) for (v in rect) reply?.writeInt(v)
                return true
            }
            CODE_DESTROY -> {
                scheduler.shutdownNow()
                synchronized(lock) { stopHid() }
                exitProcess(0)
            }
        }
        return super.onTransact(code, data, reply, flags)
    }

    // ------------------------------------------------------------- virtual HID

    private fun applyProfile(p: Int) {
        log("profile requested: $p")
        synchronized(lock) { stopHid() }
        if (p == PROFILE_NONE) return
        val s = SPECS[p] ?: SPECS.getValue(PROFILE_GENERIC)
        if (!launchHid(s)) return
        if (p != PROFILE_GENERIC) {
            // Some identities can be claimed by a kernel driver and never show up.
            // If the device doesn't appear, fall back to the generic one.
            Thread {
                if (!waitForDevice(s.name, 2500)) {
                    log("device '${s.name}' did not appear; falling back to generic")
                    synchronized(lock) { stopHid() }
                    launchHid(SPECS.getValue(PROFILE_GENERIC))
                }
            }.start()
        }
    }

    private fun launchHid(s: HidSpec): Boolean {
        return try {
            val p = ProcessBuilder("/system/bin/hid", "-").redirectErrorStream(true).start()
            synchronized(lock) {
                spec = s
                hidProcess = p
                hidOut = p.outputStream.bufferedWriter()
                resetState()
            }
            Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { log("hid: $it") }
                } catch (_: Exception) {
                }
                log("hid process ended")
                synchronized(lock) { if (hidProcess === p) hidOut = null } // switch to fallback
            }.start()

            val desc = (BASE_DESCRIPTOR + (if (s.xbox) BACK_BLOCK else IntArray(0)) + intArrayOf(0xC0))
                .joinToString(",")
            synchronized(lock) {
                sendLine(
                    """{"id":1,"command":"register","name":"${s.name}","vid":${s.vid},"pid":${s.pid},"bus":"usb","descriptor":[$desc]}"""
                )
                sendReport()
            }
            log("hid gamepad registered: ${s.name}")
            true
        } catch (e: Exception) {
            log("hid start failed: $e")
            synchronized(lock) { stopHid() }
            false
        }
    }

    /** Caller must hold [lock]. */
    private fun stopHid() {
        hidProcess?.destroy()
        hidProcess = null
        hidOut = null
        resetState()
    }

    private fun resetState() {
        buttons = 0
        dUp = false; dDown = false; dLeft = false; dRight = false
        lTrig = false; rTrig = false; back = false
        axes.fill(0f)
    }

    /** true = seen, false = not seen in time, true also if we cannot check. */
    private fun waitForDevice(name: String, timeoutMs: Long): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            val seen = deviceVisible(name) ?: return true
            if (seen) return true
            try { Thread.sleep(200) } catch (_: InterruptedException) { return true }
        }
        return false
    }

    private fun deviceVisible(name: String): Boolean? {
        val im = inputManager ?: return null
        return try {
            val iface = Class.forName("android.hardware.input.IInputManager")
            val ids = iface.getMethod("getInputDeviceIds").invoke(im) as IntArray
            val get = iface.getMethod("getInputDevice", Int::class.javaPrimitiveType)
            ids.any { (get.invoke(im, it) as? InputDevice)?.name == name }
        } catch (e: Throwable) {
            log("device check unavailable: $e")
            null
        }
    }

    private fun sendLine(s: String) {
        val w = hidOut ?: return
        try {
            w.write(s)
            w.write("\n")
            w.flush()
        } catch (e: Exception) {
            log("hid write failed: $e")
            hidOut = null
        }
    }

    private fun axisByte(v: Float): Int = (v.coerceIn(-1f, 1f) * 127f + 128f).toInt().coerceIn(0, 255)

    /** Caller must hold [lock]. */
    private fun sendReport() {
        val hat = when {
            dUp && dRight -> 2
            dRight && dDown -> 4
            dDown && dLeft -> 6
            dLeft && dUp -> 8
            dUp -> 1
            dRight -> 3
            dDown -> 5
            dLeft -> 7
            else -> 0
        }
        val report = mutableListOf(
            buttons and 0xFF,
            (buttons shr 8) and 0x7F,
            hat,
            axisByte(axes[0]), axisByte(axes[1]), axisByte(axes[2]), axisByte(axes[3]),
            if (lTrig) 255 else 0,
            if (rTrig) 255 else 0
        )
        if (spec.xbox) report.add(if (back) 1 else 0)
        sendLine("""{"id":1,"command":"report","report":[${report.joinToString(",")}]}""")
    }

    // ------------------------------------------------------------ public input

    private fun key(keyCode: Int, down: Boolean) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_SELECT || keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            log("key ${if (keyCode == KeyEvent.KEYCODE_BUTTON_START) "START" else "SELECT"} down=$down hid=${hidOut != null}")
        }
        synchronized(lock) {
            if (down) {
                pendingUp.remove(keyCode)?.cancel(false)
                downAt[keyCode] = SystemClock.uptimeMillis()
                applyKey(keyCode, true)
            } else {
                val wait = minHoldMs - (SystemClock.uptimeMillis() - (downAt[keyCode] ?: 0L))
                if (wait > 0) {
                    pendingUp.remove(keyCode)?.cancel(false)
                    pendingUp[keyCode] = scheduler.schedule({
                        synchronized(lock) {
                            pendingUp.remove(keyCode)
                            applyKey(keyCode, false)
                        }
                    }, wait, TimeUnit.MILLISECONDS)
                } else {
                    applyKey(keyCode, false)
                }
            }
        }
    }

    private fun bitFor(code: Int): Int? = if (spec.xbox) XBOX_BITS[code] else BUTTON_BITS[code]

    /** Caller must hold [lock]. */
    private fun applyKey(keyCode: Int, down: Boolean) {
        if (hidOut != null) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) lTrig = down
            if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) rTrig = down
            if (spec.xbox && keyCode == KeyEvent.KEYCODE_BUTTON_SELECT) back = down
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> dUp = down
                KeyEvent.KEYCODE_DPAD_DOWN -> dDown = down
                KeyEvent.KEYCODE_DPAD_LEFT -> dLeft = down
                KeyEvent.KEYCODE_DPAD_RIGHT -> dRight = down
                else -> bitFor(keyCode)?.let { bit ->
                    buttons = if (down) buttons or (1 shl bit) else buttons and (1 shl bit).inv()
                }
            }
            sendReport()
            if (hidOut != null) return
        }
        injectKey(keyCode, down)
    }

    private fun stick(stickId: Int, x: Float, y: Float) {
        synchronized(lock) {
            val i = stickId.coerceIn(0, 1) * 2
            axes[i] = x
            axes[i + 1] = y
            if (hidOut != null) {
                sendReport()
                if (hidOut != null) return
            }
        }
        injectStick()
    }

    // ------------------------------------------------------ fallback injection

    private fun initInjection() {
        try {
            if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("L")
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java)
                .invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            inject = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        } catch (e: Throwable) {
            log("injection init failed: $e")
        }
    }

    private fun injectKey(keyCode: Int, down: Boolean) {
        val im = inputManager ?: return
        val m = inject ?: return
        try {
            val t = SystemClock.uptimeMillis()
            val e = KeyEvent(
                t, t, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_GAMEPAD
            )
            m.invoke(im, e, 0)
        } catch (e: Throwable) {
            log("injectKey failed: $e")
        }
    }

    private fun injectStick() {
        val im = inputManager ?: return
        val m = inject ?: return
        try {
            val props = MotionEvent.PointerProperties().apply {
                this.id = 0
                toolType = MotionEvent.TOOL_TYPE_UNKNOWN
            }
            val coords = MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_X, axes[0])
                setAxisValue(MotionEvent.AXIS_Y, axes[1])
                setAxisValue(MotionEvent.AXIS_Z, axes[2])
                setAxisValue(MotionEvent.AXIS_RZ, axes[3])
            }
            val t = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(
                t, t, MotionEvent.ACTION_MOVE, 1,
                arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_JOYSTICK, 0
            )
            m.invoke(im, e, 0)
            e.recycle()
        } catch (e: Throwable) {
            log("injectStick failed: $e")
        }
    }

    // ------------------------------------------------------- touch injection
    private val touchLock = Any()
    private val touches = LinkedHashMap<Int, FloatArray>()
    private var touchDownTime = 0L

    /** Multi-finger touch injection. action: 0 = down, 1 = move, 2 = up. */
    private fun touch(slot: Int, action: Int, x: Float, y: Float) {
        val im = inputManager ?: return
        val m = inject ?: return
        synchronized(touchLock) {
            val now = SystemClock.uptimeMillis()
            val type: Int
            when (action) {
                0 -> {
                    touches[slot] = floatArrayOf(x, y)
                    if (touches.size == 1) touchDownTime = now
                    type = if (touches.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN
                }
                1 -> {
                    val p = touches[slot] ?: return
                    p[0] = x
                    p[1] = y
                    type = MotionEvent.ACTION_MOVE
                }
                else -> {
                    if (!touches.containsKey(slot)) return
                    type = if (touches.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP
                }
            }
            val slots = touches.keys.toList()
            val idx = slots.indexOf(slot)
            val props = Array(slots.size) { i ->
                MotionEvent.PointerProperties().apply {
                    this.id = slots[i]
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coords = Array(slots.size) { i ->
                val p = touches[slots[i]]!!
                MotionEvent.PointerCoords().apply {
                    this.x = p[0]
                    this.y = p[1]
                    pressure = 1f
                    size = 1f
                }
            }
            val act = if (type == MotionEvent.ACTION_POINTER_DOWN || type == MotionEvent.ACTION_POINTER_UP)
                type or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else type
            try {
                val e = MotionEvent.obtain(
                    touchDownTime, now, act, slots.size, props, coords,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                )
                m.invoke(im, e, 0)
                e.recycle()
            } catch (t: Throwable) {
                log("touch failed: $t")
            }
            if (action == 2) touches.remove(slot)
        }
    }

    // -------------------------------------------------- freeform game window

    /** Runs a shell command (we are the shell user) and returns its output. */
    private fun sh(cmd: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, TimeUnit.SECONDS)
            out
        } catch (e: Exception) {
            "error: $e"
        }
    }

    /** Finds the task of [pkg] in the activity dump. Returns (taskId, dump line). */
    private fun findTask(pkg: String): Pair<Int, String>? {
        val dump = sh("dumpsys activity activities")
        val re = Regex("#(\\d+)\\b.*?A=(?:\\d+:)?" + Regex.escape(pkg) + "\\b")
        for (line in dump.lineSequence()) {
            if (!line.contains("Task")) continue
            val m = re.find(line) ?: continue
            return m.groupValues[1].toInt() to line
        }
        return null
    }

    private val frameRe = Regex("(?<![A-Za-z])m?[Ff]rame=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
    private val captionRe = Regex("(?i)(?:captionBar|CAPTION_BAR)[^\\[]{0,40}\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")

    /**
     * How far the game's content starts below the top of its window, in pixels.
     * Freeform windows reserve a caption bar at the top (often empty on phones); the game
     * leaves that strip blank. Reads the caption inset from the window dump, or, failing
     * that, the gap between the task top and the game's own frame.
     */
    private fun topGap(pkg: String, taskTop: Int): Int {
        var best = 0
        for (cmd in arrayOf("dumpsys window windows", "dumpsys window")) {
            val lines = sh(cmd).lines()
            for (i in lines.indices) {
                if (!lines[i].contains("Window{") || !lines[i].contains("$pkg/")) continue
                var frameTop = -1
                for (j in i until minOf(lines.size, i + 250)) {
                    val line = lines[j]
                    val cap = captionRe.find(line)
                    if (cap != null) {
                        val h = cap.groupValues[4].toInt() - cap.groupValues[2].toInt()
                        if (h > 0) {
                            log("caption inset ${h}px: ${line.trim().take(160)}")
                            return h
                        }
                    }
                    if (frameTop < 0) {
                        val f = frameRe.find(line)
                        if (f != null) {
                            frameTop = f.groupValues[2].toInt()
                            log("game frame top=$frameTop: ${line.trim().take(160)}")
                        }
                    }
                }
                if (frameTop - taskTop > best) best = frameTop - taskTop
            }
            if (best > 0) return best
        }
        return best
    }

    private fun resizeTask(id: Int, l: Int, t: Int, r: Int, b: Int) {
        val o1 = sh("am task resize $id $l $t $r $b")
        val o2 = sh("am task resize $id $l,$t,$r,$b")
        log("resize $l,$t,$r,$b: ${o1.trim().take(80)} | ${o2.trim().take(80)}")
    }

    private fun pause(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    /**
     * Opens the game in a freeform window. Returns a message and the "content rect"
     * [l, t, r, b] = where the game's picture really ends up (null if it failed).
     */
    private fun launchWindowed(
        component: String, pkg: String, l: Int, t: Int, r: Int, b: Int
    ): Pair<String, IntArray?> {
        log("launchWindowed $component bounds=$l,$t,$r,$b")
        sh("settings put global enable_freeform_support 1")
        sh("settings put global force_resizable_activities 1")
        sh("settings put secure force_resizable_activities 1")
        sh("settings put global freeform_window_management 1")

        val started = sh("am start-activity --windowingMode 5 -n $component")
        log("start: ${started.trim()}")
        if (Regex("(?m)^Error").containsMatchIn(started)) {
            return ("Could not start the game: ${started.trim().take(120)}" to null)
        }

        var task: Pair<Int, String>? = null
        for (i in 0 until 12) {
            pause(500)
            task = findTask(pkg)
            if (task != null) break
        }
        val id = task?.first ?: return ("Game started, but its window was not found" to null)

        sh("am task resizeable $id 3")
        sh("am task resizeable $id true")
        resizeTask(id, l, t, r, b)
        pause(800)

        // A freeform window keeps a blank caption strip at the top and the system will not
        // let the window move above the screen. So instead of moving it up, shrink the window
        // so the game's picture (16:9 etc.) fits exactly under that strip, and let the
        // overlay cover the strip in black.
        val fullH = b - t
        val ratio = (r - l).toFloat() / fullH
        var rect = intArrayOf(l, t, r, b)
        var note = ""
        val gap = topGap(pkg, t)
        log("top gap = $gap")
        if (gap in 8..400) {
            val ch = fullH - gap
            val cw = (ch * ratio).toInt()
            val nl = l + ((r - l) - cw) / 2
            val nr = nl + cw
            resizeTask(id, nl, t, nr, b)
            pause(800)
            rect = intArrayOf(nl, t + gap, nr, b)
            note = " The ${gap}px strip at the top is covered in black."
        }

        val after = findTask(pkg)?.second ?: ""
        return when {
            after.contains("mode=freeform") ->
                ("OK: game is in a ${rect[2] - rect[0]}x${rect[3] - rect[1]} window.$note" to rect)
            after.contains("mode=fullscreen") ->
                ("Freeform did not apply. If the game was already open, close it fully and try again. " +
                        "Otherwise this phone may not support freeform windows." to null)
            else ->
                ("OK (sent).$note If the game is still fullscreen, freeform is not supported here." to rect)
        }
    }

    // ----------------------------------------------------------------- logging

    private fun log(msg: String) {
        android.util.Log.i("PadOverlay", msg)
        try {
            FileWriter(LOG_FILE, true).use { it.write("${System.currentTimeMillis()} $msg\n") }
        } catch (_: Exception) {
        }
    }
}
