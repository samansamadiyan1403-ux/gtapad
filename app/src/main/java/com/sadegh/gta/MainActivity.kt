package com.sadegh.gta

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.EditText
import java.net.*
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

// 16 buttons, hat, X Y Z Rz (sticks), Rx Ry (triggers) -> 9 bytes, report id 1
private val DESC = intArrayOf(
    0x05,0x01,0x09,0x05,0xA1,0x01,0x85,0x01,
    0x05,0x09,0x19,0x01,0x29,0x10,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x10,0x81,0x02,
    0x05,0x01,0x09,0x39,0x15,0x00,0x25,0x07,0x75,0x04,0x95,0x01,0x81,0x42,
    0x75,0x04,0x95,0x01,0x81,0x03,
    0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x32,0x09,0x35,0x15,0x00,0x26,0xFF,0x00,0x75,0x08,0x95,0x04,0x81,0x02,
    0x05,0x01,0x09,0x33,0x09,0x34,0x15,0x00,0x26,0xFF,0x00,0x75,0x08,0x95,0x02,0x81,0x02,
    0xC0
).map { it.toByte() }.toByteArray()

class MainActivity : Activity(), SensorEventListener {
    private lateinit var pad: PadView
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var mode = 0 // 1 = Bluetooth, 2 = cable/Wi-Fi (UDP to PC server)
    private var sock: DatagramSocket? = null
    private var addr: InetAddress? = null
    private val net = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { if (mode == 2 && pad.last.isNotEmpty()) pad.out(pad.last); ui.postDelayed(this, 100) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pad = PadView(this)
        pad.out = { r ->
            if (mode == 1) { val h = hid; val d = host; if (h != null && d != null) h.sendReport(d, 1, r) }
            else if (mode == 2) net.execute { try { sock?.send(DatagramPacket(r, r.size, addr, 9000)) } catch (e: Exception) {} }
        }
        pad.onMenu = { menu() }
        setContentView(pad)
        ui.post(tick)
        pad.prefs = getSharedPreferences("gta", Context.MODE_PRIVATE); pad.load()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        else start()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) start()
    }

    override fun onWindowFocusChanged(f: Boolean) {
        if (f) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onResume() {
        super.onResume()
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_GAME)
    }
    override fun onPause() { super.onPause(); (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this) }
    @Suppress("DEPRECATION")
    override fun onSensorChanged(e: SensorEvent) {
        val rot = windowManager.defaultDisplay.rotation
        pad.onAccel(e.values[1] * (if (rot == Surface.ROTATION_90) 1f else -1f))
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun start() {
        val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ad.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings("Sadegh Pad", "Phone gamepad", "Sadegh",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESC)
                h.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(),
                    object : BluetoothHidDevice.Callback() {
                        override fun onConnectionStateChanged(d: BluetoothDevice, s: Int) {
                            if (s == BluetoothProfile.STATE_CONNECTED) { host = d; say("Connected: " + d.name) }
                            else if (s == BluetoothProfile.STATE_DISCONNECTED) { host = null; say("Disconnected - tap MENU") }
                        }
                    })
                say("Ready - pair from PC, then tap MENU")
            }
            override fun onServiceDisconnected(p: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun say(s: String) = runOnUiThread { pad.status = s; pad.invalidate() }

    private fun menu() = runOnUiThread {
        val items = arrayOf("Connect: Bluetooth", "Connect: Cable / Wi-Fi (PC server)", "Edit layout (move buttons)",
            "Tilt steering: " + (if (pad.tilt) "ON" else "OFF"), "Invert tilt: " + (if (pad.invert) "ON" else "OFF"), "Reset layout")
        AlertDialog.Builder(this).setTitle("GTA V Pad").setItems(items) { _, i ->
            when (i) {
                0 -> pickBt(); 1 -> askIp(); 2 -> { pad.edit = true; pad.invalidate() }
                3 -> { pad.tilt = !pad.tilt; pad.save(); pad.invalidate() }
                4 -> { pad.invert = !pad.invert; pad.save() }
                5 -> { pad.resetLayout(); pad.invalidate() }
            }
        }.show()
    }

    private fun askIp() {
        val prefs = getSharedPreferences("pad", Context.MODE_PRIVATE)
        val et = EditText(this); et.setText(prefs.getString("ip", "192.168.42."))
        AlertDialog.Builder(this).setTitle("PC IP address").setView(et)
            .setPositiveButton("Connect") { _, _ ->
                val ip = et.text.toString().trim()
                prefs.edit().putString("ip", ip).apply()
                net.execute {
                    try {
                        sock?.close(); addr = InetAddress.getByName(ip); sock = DatagramSocket()
                        mode = 2; say("Cable/Wi-Fi mode: $ip")
                    } catch (e: Exception) { say("Bad IP") }
                }
            }.show()
    }

    private fun pickBt() = runOnUiThread {
        mode = 1
        val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val list = ad.bondedDevices.toList()
        AlertDialog.Builder(this).setTitle("Connect to PC")
            .setItems(list.map { it.name ?: it.address }.toTypedArray()) { _, i -> hid?.connect(list[i]) }
            .show()
    }
}

class PadView(c: Context) : View(c) {
    var status = "Tap the menu button to connect"
    var out: (ByteArray) -> Unit = {}
    var onMenu: () -> Unit = {}
    var last = ByteArray(0)
    var edit = false; var tilt = false; var invert = false
    private var tiltVal = 0f
    lateinit var prefs: SharedPreferences

    // kind: 0 round button, 1 rect button, 2 stick, 3 wheel, 4 pedal. bit: 0-12 pad bits, 20-23 d-pad
    class Ctl(val id: String, val kind: Int, val label: String, val bit: Int, val dx: Float, val dy: Float, val ds: Float) {
        var fx = dx; var fy = dy; var sz = ds
        var on = false; var vx = 0f; var vy = 0f; var v = 0f; var a0 = 0f
    }
    val ctl = listOf(
        Ctl("wheel", 3, "", -1, .15f, .60f, .30f), Ctl("ls", 2, "", -1, .36f, .80f, .15f), Ctl("rs", 2, "", -1, .78f, .27f, .14f),
        Ctl("brake", 4, "BRAKE\nREVERSE", -1, .80f, .66f, .07f), Ctl("gas", 4, "GAS", -1, .92f, .62f, .07f),
        Ctl("y", 0, "Y", 3, .55f, .50f, .085f), Ctl("x", 0, "X", 0, .47f, .63f, .085f),
        Ctl("b", 0, "B", 2, .63f, .63f, .085f), Ctl("a", 0, "A", 1, .55f, .76f, .085f),
        Ctl("lb", 1, "LB", 4, .08f, .11f, .07f), Ctl("rb", 1, "RB", 5, .92f, .11f, .07f),
        Ctl("back", 1, "BACK", 8, .45f, .10f, .05f), Ctl("start", 1, "START", 9, .55f, .10f, .05f),
        Ctl("menu", 1, "MENU", -1, .50f, .24f, .05f),
        Ctl("l3", 0, "L3", 10, .31f, .56f, .05f), Ctl("r3", 0, "R3", 11, .70f, .48f, .05f),
        Ctl("up", 0, "^", 20, .36f, .14f, .05f), Ctl("left", 0, "<", 22, .31f, .26f, .05f),
        Ctl("right", 0, ">", 23, .41f, .26f, .05f), Ctl("down", 0, "v", 21, .36f, .38f, .05f)
    )
    private val byId = ctl.associateBy { it.id }
    private val cap = HashMap<Int, Ctl>()
    private var sel: Ctl? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun save() = prefs.edit().apply {
        ctl.forEach { putFloat(it.id + "x", it.fx); putFloat(it.id + "y", it.fy); putFloat(it.id + "s", it.sz) }
        putBoolean("tilt", tilt); putBoolean("inv", invert)
    }.apply()

    fun load() {
        ctl.forEach { it.fx = prefs.getFloat(it.id + "x", it.dx); it.fy = prefs.getFloat(it.id + "y", it.dy); it.sz = prefs.getFloat(it.id + "s", it.ds) }
        tilt = prefs.getBoolean("tilt", false); invert = prefs.getBoolean("inv", false)
    }

    fun resetLayout() { ctl.forEach { it.fx = it.dx; it.fy = it.dy; it.sz = it.ds }; save() }

    fun onAccel(ay: Float) {
        var t = (ay / 6.9f * (if (invert) -1f else 1f)).coerceIn(-1f, 1f)
        if (abs(t) < .04f) t = 0f
        tiltVal = t
        if (tilt && !edit) push()
    }

    private fun cx(c: Ctl) = c.fx * width
    private fun cy(c: Ctl) = c.fy * height
    private fun rect(c: Ctl): RectF {
        val x = cx(c); val y = cy(c); val s = c.sz * height
        return when (c.kind) {
            1 -> RectF(x - s * 1.5f, y - s, x + s * 1.5f, y + s)
            4 -> RectF(x - s, y - s * 2.4f, x + s, y + s * 2.4f)
            else -> RectF(x - s, y - s, x + s, y + s)
        }
    }
    private fun hit(c: Ctl, x: Float, y: Float) =
        if (c.kind == 1 || c.kind == 4) rect(c).contains(x, y) else hypot(x - cx(c), y - cy(c)) <= c.sz * height * (if (c.kind >= 2) 1.1f else 1f)
    private fun topAt(x: Float, y: Float) = ctl.asReversed().firstOrNull { hit(it, x, y) }
    private fun steer() = if (tilt) tiltVal else byId["wheel"]!!.v
    private fun tb(i: Int) = RectF(width * .5f + (i - 2) * width * .09f, height * .87f, width * .5f + (i - 1) * width * .09f - 6f, height * .99f)

    private fun txt(cv: Canvas, s: String, x: Float, y: Float, size: Float) {
        p.textAlign = Paint.Align.CENTER; p.textSize = size
        val lines = s.split("\n")
        lines.forEachIndexed { i, l -> cv.drawText(l, x, y + size / 3 + (i - (lines.size - 1) / 2f) * size * 1.15f, p) }
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xFF0B0C10.toInt())
        val cols = mapOf("a" to 0xFF5FD068, "b" to 0xFFFF5A5A, "x" to 0xFF6C9CFF, "y" to 0xFFF2C94C)
        for (c in ctl) {
            val x = cx(c); val y = cy(c); val s = c.sz * height; val col = (cols[c.id] ?: 0xFF343946).toInt()
            when (c.kind) {
                0, 1 -> {
                    p.style = Paint.Style.FILL; p.color = if (c.on) 0xFF5A6178.toInt() else 0xFF1C1F27.toInt()
                    if (c.kind == 0) cv.drawCircle(x, y, s, p) else cv.drawRoundRect(rect(c), 18f, 18f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = col
                    if (c.kind == 0) cv.drawCircle(x, y, s, p) else cv.drawRoundRect(rect(c), 18f, 18f, p)
                    p.style = Paint.Style.FILL; p.color = if (cols[c.id] != null) col else 0xFFCFD3DC.toInt()
                    txt(cv, c.label, x, y, s * .8f)
                }
                2 -> {
                    p.style = Paint.Style.FILL; p.color = 0xFF171920.toInt(); cv.drawCircle(x, y, s, p)
                    p.color = 0xFF4A5063.toInt(); cv.drawCircle(x + c.vx * s * .6f, y + c.vy * s * .6f, s * .42f, p)
                }
                3 -> {
                    cv.save(); cv.rotate(steer() * 90f, x, y)
                    p.style = Paint.Style.STROKE; p.strokeWidth = s * .16f; p.color = 0xFF4A5063.toInt(); cv.drawCircle(x, y, s * .9f, p)
                    p.strokeWidth = s * .09f; cv.drawLine(x - s * .9f, y, x + s * .9f, y, p); cv.drawLine(x, y, x, y + s * .9f, p)
                    p.style = Paint.Style.FILL; p.color = 0xFFFF5A5A.toInt(); cv.drawCircle(x, y - s * .9f, s * .09f, p)
                    p.color = 0xFF23262F.toInt(); cv.drawCircle(x, y, s * .22f, p)
                    cv.restore()
                    if (tilt) { p.color = 0xFF8A90A0.toInt(); txt(cv, "TILT", x, y + s * 1.1f, s * .16f) }
                }
                4 -> {
                    val r = rect(c)
                    p.style = Paint.Style.FILL; p.color = 0xFF1C1F27.toInt(); cv.drawRoundRect(r, 18f, 18f, p)
                    p.color = if (c.id == "gas") 0xFF2E8B4A.toInt() else 0xFFA83A3A.toInt()
                    cv.drawRoundRect(RectF(r.left, r.top, r.right, r.top + r.height() * c.v), 18f, 18f, p)
                    p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = 0xFF343946.toInt(); cv.drawRoundRect(r, 18f, 18f, p)
                    p.style = Paint.Style.FILL; p.color = 0xFFCFD3DC.toInt(); txt(cv, c.label, x, y - r.height() * .3f, s * .42f)
                }
            }
            if (edit && c === sel) { p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = Color.WHITE; cv.drawRect(rect(c), p) }
        }
        p.style = Paint.Style.FILL; p.color = 0xFF8A90A0.toInt(); txt(cv, status, width / 2f, height * .04f, height * .04f)
        if (edit) {
            txt(cv, "EDIT: drag to move, then + / - to resize", width / 2f, height * .82f, height * .04f)
            listOf("DONE", "+", "-", "RESET").forEachIndexed { i, l ->
                p.style = Paint.Style.FILL; p.color = 0xFF2A2E39.toInt(); cv.drawRoundRect(tb(i), 14f, 14f, p)
                p.color = Color.WHITE; txt(cv, l, tb(i).centerX(), tb(i).centerY(), height * .05f)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        if (edit) { editTouch(e); return true }
        val skip = if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        val pressed = HashSet<Ctl>(); val live = HashSet<Int>()
        if (a != MotionEvent.ACTION_CANCEL) for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i); live.add(id)
            var c = cap[id]
            if (c == null) {
                c = topAt(x, y)
                if (c != null && c.kind >= 2) {
                    if (c.kind == 3 && tilt) c = null
                    else { cap[id] = c; c.a0 = atan2(y - cy(c), x - cx(c)) }
                }
            }
            if (c == null) continue
            val s = c.sz * height
            when (c.kind) {
                0, 1 -> pressed.add(c)
                2 -> { val vx = (x - cx(c)) / s; val vy = (y - cy(c)) / s; val m = hypot(vx, vy).coerceAtLeast(1f); c.vx = vx / m; c.vy = vy / m }
                3 -> {
                    var d = atan2(y - cy(c), x - cx(c)) - c.a0
                    while (d > PI) d -= (2 * PI).toFloat(); while (d < -PI) d += (2 * PI).toFloat()
                    c.v = (d / (PI / 2)).toFloat().coerceIn(-1f, 1f)
                }
                4 -> c.v = ((y - rect(c).top) / rect(c).height()).coerceIn(0f, 1f)
            }
        }
        cap.keys.retainAll(live)
        val held = cap.values.toSet()
        for (c in ctl) if (c.kind >= 2 && c !in held) { c.vx = 0f; c.vy = 0f; c.v = 0f }
        for (c in ctl) if (c.kind < 2) {
            val now = c in pressed
            if (now != c.on) { c.on = now; if (now) { buzz(); if (c.id == "menu") onMenu() } }
        }
        push()
        return true
    }

    private fun editTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val x = e.getX(i); val y = e.getY(i)
                val t = (0..3).firstOrNull { tb(it).contains(x, y) }
                if (t != null) {
                    when (t) {
                        0 -> edit = false
                        1 -> sel?.let { it.sz = (it.sz * 1.1f).coerceAtMost(.45f) }
                        2 -> sel?.let { it.sz = (it.sz * .9f).coerceAtLeast(.03f) }
                        3 -> resetLayout()
                    }
                    save()
                } else topAt(x, y)?.let { sel = it; cap[e.getPointerId(i)] = it }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val c = cap[e.getPointerId(i)] ?: continue
                c.fx = (e.getX(i) / width).coerceIn(.02f, .98f); c.fy = (e.getY(i) / height).coerceIn(.05f, .95f)
            }
            else -> { cap.remove(e.getPointerId(e.actionIndex)); save() }
        }
        invalidate()
    }

    @Suppress("DEPRECATION")
    private fun buzz() { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING) }

    fun push() {
        invalidate()
        val r = build()
        if (!r.contentEquals(last)) { last = r; out(r) }
    }

    private fun build(): ByteArray {
        var m = 0
        for (c in ctl) if (c.on && c.bit in 0..12) m = m or (1 shl c.bit)
        val gas = byId["gas"]!!.v; val brake = byId["brake"]!!.v
        if (brake > .5f) m = m or (1 shl 6)
        if (gas > .5f) m = m or (1 shl 7)
        val u = byId["up"]!!.on; val d = byId["down"]!!.on; val l = byId["left"]!!.on; val r = byId["right"]!!.on
        val hat = when {
            u && r -> 1; r && d -> 3; d && l -> 5; l && u -> 7
            u -> 0; r -> 2; d -> 4; l -> 6; else -> 8
        }
        fun ax(v: Float) = ((v * 127f) + 128f).toInt().coerceIn(0, 255).toByte()
        val st = steer(); val ls = byId["ls"]!!; val rs = byId["rs"]!!
        val lx = if (abs(st) > .001f) st else ls.vx
        return byteArrayOf(m.toByte(), (m shr 8).toByte(), hat.toByte(), ax(lx), ax(ls.vy), ax(rs.vx), ax(rs.vy),
            (brake * 255).toInt().toByte(), (gas * 255).toInt().toByte())
    }
}
