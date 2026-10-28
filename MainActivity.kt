package com.darius.psppad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Etat de la manette : boutons (16 bits), croix (0-7, 8 = neutre), stick gauche X/Y (0-255). */
data class PadState(val buttons: Int, val hat: Int, val lx: Int, val ly: Int)

/** Descripteur HID : 16 boutons + croix directionnelle + 4 axes (2 sticks). Rapport de 7 octets. */
object HidDescriptor {
    val BYTES: ByteArray = intArrayOf(
        0x05, 0x01,             // Usage Page: Generic Desktop
        0x09, 0x05,             // Usage: Gamepad
        0xA1, 0x01,             // Collection: Application
        // 16 boutons
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10,
        0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, 0x95, 0x10,
        0x81, 0x02,
        // Croix directionnelle (hat switch), 4 bits
        0x05, 0x01, 0x09, 0x39,
        0x15, 0x00, 0x25, 0x07,
        0x35, 0x00, 0x46, 0x3B, 0x01,
        0x65, 0x14,
        0x75, 0x04, 0x95, 0x01,
        0x81, 0x42,
        // Remplissage 4 bits
        0x65, 0x00,
        0x75, 0x04, 0x95, 0x01,
        0x81, 0x03,
        // Axes X, Y, Z, Rz (8 bits chacun)
        0x05, 0x01,
        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
        0x15, 0x00, 0x26, 0xFF, 0x00,
        0x35, 0x00, 0x46, 0xFF, 0x00,
        0x75, 0x08, 0x95, 0x04,
        0x81, 0x02,
        0xC0
    ).map { it.toByte() }.toByteArray()
}

class MainActivity : Activity() {

    private var adapter: BluetoothAdapter? = null
    private var hid: BluetoothHidDevice? = null

    @Volatile
    private var host: BluetoothDevice? = null

    @Volatile
    private var state = PadState(0, 8, 128, 128)

    private val pending = AtomicBoolean(false)
    private lateinit var sender: Handler
    private val hidExecutor = Executors.newSingleThreadExecutor()

    private lateinit var statusView: TextView
    private lateinit var menu: ScrollView
    private lateinit var pad: PadView

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            runOnUiThread {
                setStatus(
                    if (registered) "Prete. Appuie sur le bouton 1 puis associe ce telephone a la TV."
                    else "Enregistrement Bluetooth HID refuse par ce telephone."
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChanged(device: BluetoothDevice?, st: Int) {
            when (st) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    val name = device?.name ?: "TV"
                    runOnUiThread {
                        setStatus("Connectee a $name")
                        showPad()
                    }
                    push()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == host) {
                        host = null
                        runOnUiThread {
                            setStatus("Deconnectee")
                            showMenu()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        val ht = HandlerThread("hid-send", Process.THREAD_PRIORITY_URGENT_DISPLAY)
        ht.start()
        sender = Handler(ht.looper)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0E0E16.toInt())

        pad = PadView(
            this,
            onState = { b, h, x, y ->
                state = PadState(b, h, x, y)
                push()
            },
            onMenu = { showMenu() }
        )
        pad.visibility = View.GONE

        menu = buildMenu()

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.addView(pad, lp)
        root.addView(menu, lp)
        setContentView(root)

        val missing = missingPermissions()
        if (missing.isEmpty()) initHid() else requestPermissions(missing, 1)
    }

    private fun buildMenu(): ScrollView {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(64, 40, 64, 40)

        fun text(t: String, size: Float, bold: Boolean = false): TextView {
            val tv = TextView(this)
            tv.text = t
            tv.textSize = size
            tv.setTextColor(0xFFFFFFFF.toInt())
            if (bold) tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
            tv.setPadding(0, 12, 0, 12)
            return tv
        }

        fun button(label: String, click: () -> Unit): Button {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { click() }
            return b
        }

        col.addView(text("Manette PSP", 28f, true))
        statusView = text("Demarrage...", 16f)
        col.addView(statusView)
        col.addView(button("1 - Rendre ce telephone visible (appairage TV)") { makeDiscoverable() })
        col.addView(
            text(
                "Sur la TV : Parametres > Telecommandes et accessoires > Ajouter un accessoire, " +
                    "puis choisis ce telephone. La manette s'ouvre toute seule a la connexion.",
                14f
            )
        )
        col.addView(button("2 - Me connecter a un appareil deja associe") { pickDevice() })
        col.addView(button("Ouvrir la manette (test)") { showPad() })
        col.addView(
            text(
                "Dans PPSSPP : Parametres > Controles. Si un bouton est mal assigne, " +
                    "utilise \"Mappage des controles\".",
                13f
            )
        )

        val sv = ScrollView(this)
        sv.setBackgroundColor(0xFF0E0E16.toInt())
        sv.addView(col)
        return sv
    }

    private fun setStatus(s: String) {
        statusView.text = s
    }

    private fun showPad() {
        menu.visibility = View.GONE
        pad.visibility = View.VISIBLE
    }

    private fun showMenu() {
        pad.visibility = View.GONE
        menu.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (pad.visibility == View.VISIBLE) showMenu() else super.onBackPressed()
    }

    private fun missingPermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < 31) return emptyArray()
        return arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            initHid()
        } else {
            setStatus("Autorise le Bluetooth (appareils a proximite) puis relance l'app.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun initHid() {
        val a = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter = a
        if (a == null) {
            setStatus("Bluetooth indisponible sur ce telephone.")
            return
        }
        if (!a.isEnabled) {
            setStatus("Active le Bluetooth puis relance l'app.")
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        a.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "Manette PSP",
                    "Manette PSP",
                    "Darius",
                    BluetoothHidDevice.SUBCLASS1_COMBO,
                    HidDescriptor.BYTES
                )
                h.registerApp(sdp, null, null, hidExecutor, callback)
            }

            override fun onServiceDisconnected(profile: Int) {
                hid = null
            }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun makeDiscoverable() {
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
        i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        startActivity(i)
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        val bonded = adapter?.bondedDevices?.toList() ?: emptyList()
        if (bonded.isEmpty()) {
            setStatus("Aucun appareil associe. Utilise d'abord le bouton 1.")
            return
        }
        val names = bonded.map { it.name ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choisis ta TV / box")
            .setItems(names) { _, i -> hid?.connect(bonded[i]) }
            .show()
    }

    /** Envoie toujours le dernier etat connu ; les changements rapides sont fusionnes (pas de file d'attente). */
    private fun push() {
        if (pending.compareAndSet(false, true)) {
            sender.post {
                pending.set(false)
                sendNow()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNow() {
        val h = hid ?: return
        val d = host ?: return
        val s = state
        val r = ByteArray(7)
        r[0] = (s.buttons and 0xFF).toByte()
        r[1] = ((s.buttons shr 8) and 0xFF).toByte()
        r[2] = s.hat.toByte()
        r[3] = s.lx.toByte()
        r[4] = s.ly.toByte()
        r[5] = 128.toByte()
        r[6] = 128.toByte()
        h.sendReport(d, 0, r)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        try {
            hid?.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (_: Exception) {
        }
    }
}

/** Manette PSP plein ecran, multitouch. */
class PadView(
    ctx: Context,
    private val onState: (Int, Int, Int, Int) -> Unit,
    private val onMenu: () -> Unit
) : View(ctx) {

    companion object {
        // Numero de bit = (usage HID - 1). PPSSPP/Android les voit comme A, B, X, Y, L1, R1, Select, Start, Mode.
        const val B_CROSS = 0
        const val B_CIRCLE = 1
        const val B_SQUARE = 3
        const val B_TRIANGLE = 4
        const val B_L = 6
        const val B_R = 7
        const val B_SELECT = 10
        const val B_START = 11
        const val B_HOME = 12
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
        isFakeBoldText = true
    }
    private val path = Path()

    private var dpCx = 0f
    private var dpCy = 0f
    private var dpHalf = 0f
    private var stCx = 0f
    private var stCy = 0f
    private var stR = 0f
    private var fcCx = 0f
    private var fcCy = 0f
    private var fcOff = 0f
    private var fcR = 0f
    private var slack = 0f
    private val lRect = RectF()
    private val rRect = RectF()
    private val selRect = RectF()
    private val homeRect = RectF()
    private val startRect = RectF()
    private val menuRect = RectF()

    private var stickId = -1
    private var stickDx = 0f
    private var stickDy = 0f

    private var curBtn = 0
    private var curHat = 8
    private var curLx = 128
    private var curLy = 128

    private val faceColors = intArrayOf(0xFF3DDC84.toInt(), 0xFFFF4D5A.toInt(), 0xFF4D9DFF.toInt(), 0xFFFF7BC2.toInt())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val W = w.toFloat()
        val H = h.toFloat()
        dpCx = 0.17f * W
        dpCy = 0.40f * H
        dpHalf = 0.22f * H
        stCx = dpCx
        stCy = 0.82f * H
        stR = 0.14f * H
        fcCx = 0.83f * W
        fcCy = 0.42f * H
        fcOff = 0.17f * H
        fcR = 0.10f * H
        slack = 0.02f * H
        lRect.set(0.03f * W, 0.04f * H, 0.27f * W, 0.19f * H)
        rRect.set(0.73f * W, 0.04f * H, 0.97f * W, 0.19f * H)
        selRect.set(0.30f * W, 0.84f * H, 0.42f * W, 0.94f * H)
        homeRect.set(0.44f * W, 0.84f * H, 0.56f * W, 0.94f * H)
        startRect.set(0.58f * W, 0.84f * H, 0.70f * W, 0.94f * H)
        menuRect.set(0.44f * W, 0.03f * H, 0.56f * W, 0.12f * H)
        tp.textSize = 0.055f * H
    }

    private fun inRect(r: RectF, x: Float, y: Float): Boolean =
        x >= r.left - slack && x <= r.right + slack && y >= r.top - slack && y <= r.bottom + slack

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val act = e.actionMasked
        val allUp = act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL
        val lifted = if (act == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        val downIdx =
            if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN) e.actionIndex else -1

        var btn = 0
        var up = false
        var right = false
        var down = false
        var left = false
        var lx = 128
        var ly = 128
        var stickIdx = -1
        var menuTapped = false

        if (allUp) {
            stickId = -1
        } else if (stickId != -1) {
            val i = e.findPointerIndex(stickId)
            if (i >= 0 && i != lifted) stickIdx = i else stickId = -1
        }

        if (!allUp) {
            for (i in 0 until e.pointerCount) {
                if (i == lifted || i == stickIdx) continue
                val x = e.getX(i)
                val y = e.getY(i)

                // Le stick prend le doigt qui vient de se poser dans sa zone
                if (stickId == -1 && i == downIdx && hypot(x - stCx, y - stCy) < stR * 1.6f) {
                    stickId = e.getPointerId(i)
                    stickIdx = i
                    continue
                }

                // Boutons de face (zones larges : on peut appuyer sur deux a la fois)
                val reach = fcR * 1.3f
                if (hypot(x - fcCx, y - (fcCy - fcOff)) < reach) btn = btn or (1 shl B_TRIANGLE)
                if (hypot(x - fcCx, y - (fcCy + fcOff)) < reach) btn = btn or (1 shl B_CROSS)
                if (hypot(x - (fcCx - fcOff), y - fcCy) < reach) btn = btn or (1 shl B_SQUARE)
                if (hypot(x - (fcCx + fcOff), y - fcCy) < reach) btn = btn or (1 shl B_CIRCLE)

                if (inRect(lRect, x, y)) btn = btn or (1 shl B_L)
                if (inRect(rRect, x, y)) btn = btn or (1 shl B_R)
                if (inRect(selRect, x, y)) btn = btn or (1 shl B_SELECT)
                if (inRect(startRect, x, y)) btn = btn or (1 shl B_START)
                if (inRect(homeRect, x, y)) btn = btn or (1 shl B_HOME)
                if (i == downIdx && inRect(menuRect, x, y)) menuTapped = true

                // Croix directionnelle (8 directions)
                val dx = x - dpCx
                val dy = y - dpCy
                val d = hypot(dx, dy)
                if (d < dpHalf * 1.3f && d > dpHalf * 0.14f) {
                    val ax = dx / d
                    val ay = dy / d
                    if (ay < -0.38f) up = true
                    if (ay > 0.38f) down = true
                    if (ax < -0.38f) left = true
                    if (ax > 0.38f) right = true
                }
            }
        }

        if (stickIdx != -1) {
            var dx = e.getX(stickIdx) - stCx
            var dy = e.getY(stickIdx) - stCy
            val d = hypot(dx, dy)
            if (d > stR) {
                dx = dx / d * stR
                dy = dy / d * stR
            }
            stickDx = dx
            stickDy = dy
            if (d > stR * 0.08f) {
                lx = ((dx / stR + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
                ly = ((dy / stR + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
            }
        } else {
            stickDx = 0f
            stickDy = 0f
        }

        if (menuTapped) {
            onMenu()
            return true
        }

        val hat = when {
            up && right -> 1
            right && down -> 3
            down && left -> 5
            left && up -> 7
            up -> 0
            right -> 2
            down -> 4
            left -> 6
            else -> 8
        }

        val changed = btn != curBtn || hat != curHat || lx != curLx || ly != curLy
        if (changed) {
            val newPress = (btn and curBtn.inv()) != 0 || (hat != 8 && curHat == 8)
            if (newPress) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            curBtn = btn
            curHat = hat
            curLx = lx
            curLy = ly
            onState(btn, hat, lx, ly)
        }
        invalidate()
        return true
    }

    private fun pressed(bit: Int) = (curBtn shr bit) and 1 == 1

    private fun label(c: Canvas, t: String, cx: Float, cy: Float) {
        c.drawText(t, cx, cy - (tp.descent() + tp.ascent()) / 2f, tp)
    }

    private fun pill(c: Canvas, r: RectF, text: String, on: Boolean, size: Float) {
        p.style = Paint.Style.FILL
        p.color = if (on) 0xFF6C6C90.toInt() else 0xFF23232F.toInt()
        val rad = r.height() / 2f
        c.drawRoundRect(r, rad, rad, p)
        val old = tp.textSize
        tp.textSize = size
        label(c, text, r.centerX(), r.centerY())
        tp.textSize = old
    }

    private fun arm(c: Canvas, l: Float, t: Float, r: Float, b: Float, on: Boolean) {
        p.style = Paint.Style.FILL
        p.color = if (on) 0xFF6C6C90.toInt() else 0xFF262633.toInt()
        val rad = dpHalf * 0.12f
        c.drawRoundRect(l, t, r, b, rad, rad, p)
    }

    private fun faceButton(c: Canvas, cx: Float, cy: Float, type: Int, on: Boolean) {
        val col = faceColors[type]
        p.style = Paint.Style.FILL
        p.color = if (on) col else 0xFF1E1E2A.toInt()
        c.drawCircle(cx, cy, fcR, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = fcR * 0.09f
        p.color = col
        c.drawCircle(cx, cy, fcR, p)

        p.color = if (on) 0xFF0E0E16.toInt() else col
        p.strokeWidth = fcR * 0.12f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val s = fcR * 0.45f
        when (type) {
            0 -> {
                path.reset()
                path.moveTo(cx, cy - s)
                path.lineTo(cx + s * 0.95f, cy + s * 0.75f)
                path.lineTo(cx - s * 0.95f, cy + s * 0.75f)
                path.close()
                c.drawPath(path, p)
            }
            1 -> c.drawCircle(cx, cy, s, p)
            2 -> {
                c.drawLine(cx - s, cy - s, cx + s, cy + s, p)
                c.drawLine(cx + s, cy - s, cx - s, cy + s, p)
            }
            else -> c.drawRect(cx - s * 0.9f, cy - s * 0.9f, cx + s * 0.9f, cy + s * 0.9f, p)
        }
        p.style = Paint.Style.FILL
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF0E0E16.toInt())

        // Gachettes
        pill(c, lRect, "L", pressed(B_L), tp.textSize)
        pill(c, rRect, "R", pressed(B_R), tp.textSize)

        // Croix directionnelle
        val upOn = curHat == 7 || curHat == 0 || curHat == 1
        val rightOn = curHat in 1..3
        val downOn = curHat in 3..5
        val leftOn = curHat in 5..7
        val aw = dpHalf * 0.34f
        arm(c, dpCx - aw, dpCy - dpHalf, dpCx + aw, dpCy, upOn)
        arm(c, dpCx - aw, dpCy, dpCx + aw, dpCy + dpHalf, downOn)
        arm(c, dpCx - dpHalf, dpCy - aw, dpCx, dpCy + aw, leftOn)
        arm(c, dpCx, dpCy - aw, dpCx + dpHalf, dpCy + aw, rightOn)
        p.style = Paint.Style.FILL
        p.color = 0xFF262633.toInt()
        c.drawRect(dpCx - aw, dpCy - aw, dpCx + aw, dpCy + aw, p)

        // Stick analogique
        p.style = Paint.Style.FILL
        p.color = 0xFF1A1A26.toInt()
        c.drawCircle(stCx, stCy, stR, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = stR * 0.06f
        p.color = 0xFF3A3A52.toInt()
        c.drawCircle(stCx, stCy, stR, p)
        p.style = Paint.Style.FILL
        p.color = if (stickId != -1) 0xFF7A7AAA.toInt() else 0xFF50507A.toInt()
        c.drawCircle(stCx + stickDx, stCy + stickDy, stR * 0.5f, p)

        // Boutons de face
        faceButton(c, fcCx, fcCy - fcOff, 0, pressed(B_TRIANGLE))
        faceButton(c, fcCx, fcCy + fcOff, 2, pressed(B_CROSS))
        faceButton(c, fcCx - fcOff, fcCy, 3, pressed(B_SQUARE))
        faceButton(c, fcCx + fcOff, fcCy, 1, pressed(B_CIRCLE))

        // Select / Home / Start
        val small = tp.textSize * 0.62f
        pill(c, selRect, "SELECT", pressed(B_SELECT), small)
        pill(c, homeRect, "HOME", pressed(B_HOME), small)
        pill(c, startRect, "START", pressed(B_START), small)

        // Retour au menu (discret)
        p.style = Paint.Style.FILL
        p.color = 0xFF191924.toInt()
        c.drawRoundRect(menuRect, 20f, 20f, p)
        val old = tp.color
        tp.color = 0xFF777790.toInt()
        val os = tp.textSize
        tp.textSize = small * 0.9f
        label(c, "MENU", menuRect.centerX(), menuRect.centerY())
        tp.textSize = os
        tp.color = old
    }
}
