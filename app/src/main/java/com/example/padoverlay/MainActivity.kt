package com.example.padoverlay

import android.app.Activity
import android.app.StatusBarManager
import android.graphics.drawable.Icon
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.DisplayMetrics
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var keyText: TextView
    private lateinit var axisText: TextView
    private lateinit var scroll: ScrollView

    // 0 = follow system, 1 = light, 2 = dark
    private fun themeMode() = getSharedPreferences("ui", MODE_PRIVATE).getInt("theme", 0)

    private fun isNight(): Boolean = when (themeMode()) {
        1 -> false
        2 -> true
        else -> Build.VERSION.SDK_INT >= 29 &&
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(
            when (themeMode()) {
                1 -> R.style.AppThemeLight
                2 -> R.style.AppThemeDark
                else -> R.style.AppTheme
            }
        )
        super.onCreate(savedInstanceState)

        val night = isNight()
        val bg = if (night) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (night) 0xFFEDEDED.toInt() else 0xFF1B1B1B.toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 48)
        }
        fun btn(label: String, action: () -> Unit) {
            col.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }
        fun title(t: String) {
            col.addView(TextView(this).apply {
                text = t
                textSize = 16f
                setTextColor(fg)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 48, 0, 12)
            })
        }
        fun body(t: String) {
            col.addView(TextView(this).apply {
                text = t
                textSize = 14f
                setTextColor(fg)
            })
        }

        // ------------------------------------------------------ appearance
        title("APPEARANCE")
        val label = when (themeMode()) { 1 -> "Light"; 2 -> "Dark"; else -> "Auto (follow system)" }
        btn("Theme: $label  (tap to change)") {
            getSharedPreferences("ui", MODE_PRIVATE).edit()
                .putInt("theme", (themeMode() + 1) % 3).apply()
            recreate()
        }

        // ------------------------------------------------------ input mode
        title("INPUT MODE")
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val modeLabel = if (ui.getInt("mode", 0) == 1) "Touch mapping" else "Gamepad (HID)"
        btn("Mode: $modeLabel  (tap to change)") {
            ui.edit().putInt("mode", 1 - ui.getInt("mode", 0)).apply()
            toast("Stop and start the overlay to apply")
            recreate()
        }
        body(
            "Gamepad: acts as a real controller (works in games with controller support).\n" +
            "Touch mapping: taps the game's own on-screen buttons. Use it for games that ignore " +
            "the gamepad, like Dead Cells. Start the overlay, tap EDIT, drag each round marker " +
            "onto the matching button in the game, then tap the save icon. The left stick acts " +
            "as the four arrow markers."
        )
        val profLabel = PadProfile.values()[ui.getInt("profile", 0).coerceIn(0, PadProfile.values().size - 1)].label
        btn("Controller type: $profLabel  (tap to change)") {
            ui.edit().putInt("profile", (ui.getInt("profile", 0) + 1) % PadProfile.values().size).apply()
            toast("Stop and start the overlay to apply")
            recreate()
        }
        body(
            "Controller type (Gamepad mode only):\n" +
            "Generic: plain controller.\n" +
            "Xbox: appears as an Xbox One S controller; labels A B X Y, LB RB LT RT, VIEW, MENU.\n" +
            "DualShock 4: PlayStation symbols and SHARE / OPTIONS labels, appears as 'Wireless Controller'.\n" +
            "DualSense (PS5): same symbols, CREATE / OPTIONS labels, appears as 'DualSense Wireless Controller'.\n" +
            "If Xbox is not accepted by the phone it falls back to Generic by itself."
        )

        // ---------------------------------------------------------- setup
        title("SETUP")
        btn("1. Allow overlay") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        btn("2. Allow Shizuku") {
            if (!Shizuku.pingBinder()) toast("Start Shizuku first")
            else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                toast("Already granted")
            else Shizuku.requestPermission(1)
        }
        btn("3. Start overlay") { startOverlay() }
        btn("Stop overlay") { stopService(Intent(this, OverlayService::class.java)) }
        btn("Add quick setting tile") { addTile() }
        btn("Reset layout") {
            getSharedPreferences("pad", MODE_PRIVATE).edit().clear().apply()
            toast("Layout reset. Stop and start the overlay again.")
        }

        // ------------------------------------------------------ game window
        title("GAME WINDOW (WORKS ON ANY PHONE)")
        body(
            "Runs the game in a centred freeform window so the empty sides are free for the controls. " +
            "Before you start: Shizuku running, phone in landscape, and the game fully closed. " +
            "A black surround covers everything outside the game so stray taps do nothing; " +
            "close the overlay with the X on the right menu."
        )
        btn("Game: ${ui.getString("game_name", "none")}  (tap to pick)") { pickGame() }
        btn("Window shape: ${Win.LABELS[ui.getInt("aspect", 0).coerceIn(0, Win.LABELS.size - 1)]}  (tap to change)") {
            ui.edit().putInt("aspect", (ui.getInt("aspect", 0) + 1) % Win.LABELS.size).remove("win_cr").apply()
            recreate()
        }
        btn("Layout: ${if (ui.getInt("win_mode", 0) == 1) "Window bars" else "Fixed bars (Game Space)"}  (tap to change)") {
            ui.edit().putInt("win_mode", 1 - ui.getInt("win_mode", 0)).apply()
            toast("Stop and start the overlay to apply")
            recreate()
        }
        btn("Black surround: ${if (ui.getInt("backdrop", 1) == 1) "On" else "Off"}  (tap to change)") {
            ui.edit().putInt("backdrop", 1 - ui.getInt("backdrop", 1)).apply()
            toast("Stop and start the overlay to apply")
            recreate()
        }
        btn("Launch game in window + start overlay") { launchWindowed() }

        // ------------------------------------------------------- MacroDroid
        title("MACRODROID: AUTO START / STOP")
        body(
            "1. New macro. Trigger: Applications > Application Launched, pick your game.\n" +
            "2. Action: Applications > Send Intent.\n" +
            "3. Target: Broadcast Receiver.\n" +
            "4. Action, Package and Class: tap the copy buttons below, then paste each one into MacroDroid.\n" +
            "5. Make a second macro. Trigger: Application Closed, same game. Same intent, but copy the STOP action.\n\n" +
            "If nothing happens, set Target to Activity and use the activity class instead. " +
            "Also turn off battery optimization for this app, and keep Shizuku running."
        )
        btn("Copy START action") { copy(OverlayControl.ACTION_START) }
        btn("Copy STOP action") { copy(OverlayControl.ACTION_STOP) }
        btn("Copy package") { copy(packageName) }
        btn("Copy receiver class") { copy(ControlReceiver::class.java.name) }
        btn("Copy activity class (backup)") { copy(ControlActivity::class.java.name) }
        btn("Battery optimization settings") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        // ------------------------------------------------------- input test
        title("INPUT TEST")
        body("With the overlay running, press the on-screen controls while this screen is open.")
        keyText = TextView(this).apply { text = "key: -"; setTextColor(fg); setPadding(0, 12, 0, 0) }
        axisText = TextView(this).apply { text = "axes: -"; setTextColor(fg) }
        col.addView(keyText)
        col.addView(axisText)

        // Scrollable, and kept clear of the status bar / camera cutout / nav bar
        scroll = ScrollView(this).apply {
            addView(col)
            clipToPadding = false
            setBackgroundColor(bg)
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val i = insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                    v.setPadding(i.left, i.top, i.right, i.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(
                        insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }
        setContentView(scroll)
        applySystemBars(night, bg)

        // Buttons that refresh the screen (recreate) used to jump back to the top.
        // Put the scroll position back once the layout is ready.
        val savedY = savedInstanceState?.getInt("scrollY", 0) ?: 0
        if (savedY > 0) {
            scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    scroll.viewTreeObserver.removeOnPreDrawListener(this)
                    scroll.scrollTo(0, savedY)
                    return true
                }
            })
        }
    }

    /** Dark status/navigation bar icons on a light page, light icons on a dark page. */
    @Suppress("DEPRECATION")
    private fun applySystemBars(night: Boolean, bg: Int) {
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = bg
            window.navigationBarColor = bg
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (night) 0 else mask, mask)
        } else {
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val v = window.decorView
            v.systemUiVisibility =
                if (night) v.systemUiVisibility and light.inv() else v.systemUiVisibility or light
        }
    }

    // ------------------------------------------------------------ test screen
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::scroll.isInitialized) outState.putInt("scrollY", scroll.scrollY)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.repeatCount == 0) {
            keyText.text = "key: ${if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"} " +
                    "${KeyEvent.keyCodeToString(e.keyCode)}  src=0x${Integer.toHexString(e.source)}  " +
                    "dev=${e.device?.name ?: "none"}"
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_MOVE && e.source and 0x10 != 0) {
            fun a(axis: Int) = String.format("%.2f", e.getAxisValue(axis))
            axisText.text = "axes: X=${a(MotionEvent.AXIS_X)} Y=${a(MotionEvent.AXIS_Y)} " +
                    "Z=${a(MotionEvent.AXIS_Z)} RZ=${a(MotionEvent.AXIS_RZ)}\n" +
                    "LT=${a(MotionEvent.AXIS_LTRIGGER)} RT=${a(MotionEvent.AXIS_RTRIGGER)} " +
                    "BRAKE=${a(MotionEvent.AXIS_BRAKE)} GAS=${a(MotionEvent.AXIS_GAS)} " +
                    "HAT=${a(MotionEvent.AXIS_HAT_X)},${a(MotionEvent.AXIS_HAT_Y)}"
        }
        return super.dispatchGenericMotionEvent(e)
    }

    // ---------------------------------------------------------------- helpers
    // ------------------------------------------------------- game window helpers
    private var svc: IBinder? = null
    private var pending: ((IBinder) -> Unit)? = null
    private val svcConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            svc = binder
            pending?.invoke(binder)
            pending = null
        }
        override fun onServiceDisconnected(name: ComponentName) {
            svc = null
        }
    }

    private fun withService(block: (IBinder) -> Unit) {
        val b = svc
        if (b != null && b.isBinderAlive) {
            block(b)
            return
        }
        pending = block
        Shizuku.bindUserService(PadService.args(this), svcConn)
    }

    override fun onDestroy() {
        runCatching { Shizuku.unbindUserService(PadService.args(this), svcConn, false) }
        super.onDestroy()
    }

    private fun screenPx(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    private fun pickGame() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return toast("No apps found")
        AlertDialog.Builder(this)
            .setTitle("Pick the game")
            .setItems(apps.map { it.first }.toTypedArray()) { _, i ->
                getSharedPreferences("ui", MODE_PRIVATE).edit()
                    .putString("game_pkg", apps[i].second)
                    .putString("game_name", apps[i].first)
                    .apply()
                recreate()
            }
            .show()
    }

    private fun launchWindowed() {
        val ui = getSharedPreferences("ui", MODE_PRIVATE)
        val pkg = ui.getString("game_pkg", null) ?: return toast("Pick a game first")
        if (!Settings.canDrawOverlays(this)) return toast("Allow overlay first (step 1)")
        if (!Shizuku.pingBinder()) return toast("Start Shizuku first")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return toast("Allow Shizuku first (step 2)")
        val comp = packageManager.getLaunchIntentForPackage(pkg)?.component
            ?: return toast("That app has no launcher entry")
        val (sw, sh) = screenPx()
        if (sh > sw) return toast("Rotate the phone to landscape first")
        val bnd = Win.bounds(sw, sh, Win.ratio(ui.getInt("aspect", 0)))
        ui.edit().putInt("win_mode", 1).apply()
        toast("Launching ${ui.getString("game_name", pkg)}...")
        withService { b ->
            Thread {
                val d = Parcel.obtain()
                val r = Parcel.obtain()
                val status = try {
                    d.writeString(comp.flattenToString())
                    d.writeString(pkg)
                    d.writeInt(bnd[0]); d.writeInt(bnd[1]); d.writeInt(bnd[2]); d.writeInt(bnd[3])
                    b.transact(InputService.CODE_LAUNCH, d, r, 0)
                    val msg = r.readString() ?: "No reply from the helper"
                    if (r.dataAvail() > 0 && r.readInt() == 1) {
                        val cl = r.readInt()
                        val ct = r.readInt()
                        val cr = r.readInt()
                        val cb = r.readInt()
                        ui.edit()
                            .putInt("win_cl", cl).putInt("win_ct", ct)
                            .putInt("win_cr", cr).putInt("win_cb", cb)
                            .putInt("win_sw", sw).putInt("win_sh", sh)
                            .apply()
                    }
                    msg
                } catch (e: Exception) {
                    "Error: $e"
                } finally {
                    d.recycle()
                    r.recycle()
                }
                runOnUiThread {
                    toast(status, long = true)
                    if (status.startsWith("OK")) restartOverlay()
                }
            }.start()
        }
    }

    private fun restartOverlay() {
        stopService(Intent(this, OverlayService::class.java))
        Handler(Looper.getMainLooper()).postDelayed({ startOverlay() }, 700)
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            val sbm = getSystemService(StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, OverlayTileService::class.java),
                "Pad overlay",
                Icon.createWithResource(this, R.drawable.ic_stat_pad),
                mainExecutor
            ) { result ->
                val added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                        result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                toast(if (added) "Tile added" else "Tile not added")
            }
        } else {
            toast("Open Quick Settings, tap the pencil icon and drag 'Pad overlay' in", long = true)
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) return toast("Allow overlay first (step 1)")
        if (!Shizuku.pingBinder()) return toast("Start Shizuku first")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            return toast("Allow Shizuku first (step 2)")
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    private fun copy(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("PadOverlay", text))
        toast("Copied: $text")
    }

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}
