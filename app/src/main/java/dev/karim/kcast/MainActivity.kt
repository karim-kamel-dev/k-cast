package dev.karim.kcast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var shareBtn: Button
    private lateinit var shareInfo: TextView
    private var preset = 0
    private val presetBtns = ArrayList<Button>()
    private lateinit var devices: LinearLayout
    private lateinit var pinField: EditText
    private lateinit var ipField: EditText
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, Pair<String, Int>>()
    private val toResolve = ArrayList<NsdServiceInfo>()
    private var resolving = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.text = t; tv.textSize = size; tv.setTextColor(color)
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        return tv
    }

    private fun button(t: String, bg: Int): Button {
        val b = Button(this)
        b.text = t; b.setTextColor(Color.WHITE); b.setBackgroundColor(bg); b.isAllCaps = false; b.textSize = 18f
        return b
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sc = ScrollView(this)
        sc.setBackgroundColor(Color.BLACK)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(40), dp(20), dp(30))
        sc.addView(col)
        col.addView(label("k cast", 34f, Color.WHITE, true))
        col.addView(label("Show this phone on another phone, over WiFi.", 15f, Color.LTGRAY))

        col.addView(space(28))
        col.addView(label("1. On the phone to SHOW", 16f, Color.parseColor("#7CFFB2"), true))
        val pr = LinearLayout(this); pr.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("Fast", "Balanced", "Quality")
        for (k in 0..2) {
            val pb = button(names[k], Color.parseColor("#2A2A2A"))
            pb.textSize = 15f
            pb.setOnClickListener { preset = k; colorPresets() }
            presetBtns.add(pb)
            pr.addView(pb, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        colorPresets()
        col.addView(label("Speed (Fast = least delay, lower sharpness)", 13f, Color.LTGRAY).apply { setPadding(0, dp(8), 0, dp(4)) })
        col.addView(pr)
        shareBtn = button("Share my screen", Color.parseColor("#1B8F4E"))
        shareBtn.setOnClickListener { onShareClick() }
        col.addView(shareBtn, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
        shareInfo = label("", 18f)
        shareInfo.setPadding(0, dp(10), 0, 0)
        col.addView(shareInfo)

        col.addView(space(32))
        col.addView(label("2. On the phone to WATCH", 16f, Color.parseColor("#7CC8FF"), true))
        col.addView(label("Type the 4-digit code shown on the other phone, then tap its name.", 14f, Color.LTGRAY))
        pinField = EditText(this)
        pinField.hint = "Code"; pinField.setHintTextColor(Color.GRAY); pinField.setTextColor(Color.WHITE)
        pinField.inputType = InputType.TYPE_CLASS_NUMBER; pinField.textSize = 24f
        col.addView(pinField, LinearLayout.LayoutParams(dp(160), -2))
        devices = LinearLayout(this); devices.orientation = LinearLayout.VERTICAL
        col.addView(devices)
        col.addView(space(16))
        col.addView(label("Not listed? Type its address:", 14f, Color.LTGRAY))
        ipField = EditText(this)
        ipField.hint = "192.168.1.23"; ipField.setHintTextColor(Color.GRAY); ipField.setTextColor(Color.WHITE)
        ipField.inputType = InputType.TYPE_CLASS_PHONE
        col.addView(ipField)
        val go = button("Watch", Color.parseColor("#1B5E9F"))
        go.setOnClickListener { watch(ipField.text.toString().trim(), CastService.PORT) }
        col.addView(go, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(8) })
        setContentView(sc)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    private fun colorPresets() { for (k in 0..2) presetBtns[k].setBackgroundColor(Color.parseColor(if (k == preset) "#1B8F4E" else "#2A2A2A")) }

    private fun space(h: Int): View { val v = View(this); v.minimumHeight = dp(h); return v }

    private fun onShareClick() {
        if (CastService.running) {
            startService(Intent(this, CastService::class.java).setAction(CastService.ACTION_STOP))
        } else {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), 42)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 42) {
            if (res == RESULT_OK && data != null) {
                val i = Intent(this, CastService::class.java).setAction(CastService.ACTION_START)
                i.putExtra("code", res); i.putExtra("data", data); i.putExtra("preset", preset)
                startForegroundService(i)
            } else Toast.makeText(this, "Screen sharing was not allowed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun watch(host: String, port: Int) {
        val pin = pinField.text.toString().trim().toIntOrNull()
        if (host.isEmpty()) { Toast.makeText(this, "Type the address first", Toast.LENGTH_SHORT).show(); return }
        if (pin == null) { Toast.makeText(this, "Type the 4-digit code first", Toast.LENGTH_SHORT).show(); return }
        val i = Intent(this, WatchActivity::class.java)
        i.putExtra("host", host); i.putExtra("port", port); i.putExtra("pin", pin)
        startActivity(i)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (CastService.running) {
                shareBtn.text = "Stop sharing"
                shareBtn.setBackgroundColor(Color.parseColor("#B3261E"))
                val ips = CastService.localIps().joinToString("  ")
                shareInfo.text = "Code: " + CastService.pin + "\nAddress: " + ips + "\nWatching now: " + CastService.viewers
            } else {
                shareBtn.text = "Share my screen"
                shareBtn.setBackgroundColor(Color.parseColor("#1B8F4E"))
                shareInfo.text = CastService.error
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() { super.onResume(); ui.post(tick); startDiscovery() }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick); stopDiscovery() }

    private fun refreshDevices() {
        devices.removeAllViews()
        for ((name, hp) in found) {
            val b = button(name.removePrefix("kcast-").replace("_", " "), Color.parseColor("#2A2A2A"))
            b.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            b.setOnClickListener { watch(hp.first, hp.second) }
            devices.addView(b, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(6) })
        }
    }

    private fun resolveNext() {
        if (resolving || toResolve.isEmpty()) return
        val si = toResolve.removeAt(0)
        resolving = true
        try {
            @Suppress("DEPRECATION")
            nsd?.resolveService(si, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, e: Int) { ui.post { resolving = false; resolveNext() } }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    val h = s.host?.hostAddress
                    ui.post {
                        if (h != null) { found[s.serviceName] = Pair(h, s.port); refreshDevices() }
                        resolving = false; resolveNext()
                    }
                }
            })
        } catch (e: Exception) { resolving = false }
    }

    private fun startDiscovery() {
        if (discovery != null) return
        try {
            val m = getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = m
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
                override fun onServiceFound(s: NsdServiceInfo) { ui.post { toResolve.add(s); resolveNext() } }
                override fun onServiceLost(s: NsdServiceInfo) { ui.post { found.remove(s.serviceName); refreshDevices() } }
            }
            discovery = l
            m.discoverServices(CastService.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) { discovery = null }
    }

    private fun stopDiscovery() {
        try { discovery?.let { nsd?.stopServiceDiscovery(it) } } catch (e: Exception) { }
        discovery = null
        found.clear(); toResolve.clear(); resolving = false
        devices.removeAllViews()
    }
}
