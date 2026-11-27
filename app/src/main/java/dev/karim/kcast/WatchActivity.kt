package dev.karim.kcast

import android.app.Activity
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch

class WatchActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var sv: SurfaceView
    private lateinit var msg: TextView
    private val surfaceReady = CountDownLatch(1)
    @Volatile private var socket: Socket? = null
    @Volatile private var stopped = false
    private var vw = 0
    private var vh = 0

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        sv = SurfaceView(this)
        root.addView(sv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        msg = TextView(this)
        msg.setTextColor(Color.WHITE); msg.textSize = 18f; msg.gravity = Gravity.CENTER
        msg.text = "Connecting..."
        root.addView(msg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surfaceReady.countDown() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
        val host = intent.getStringExtra("host") ?: ""
        val port = intent.getIntExtra("port", CastService.PORT)
        val pin = intent.getIntExtra("pin", 0)
        Thread({ run(host, port, pin) }, "kcast-watch").start()
    }

    private fun fit() {
        if (vw <= 0 || vh <= 0) return
        val cw = root.width; val ch = root.height
        if (cw <= 0 || ch <= 0) return
        val s = minOf(cw.toFloat() / vw, ch.toFloat() / vh)
        val lp = sv.layoutParams
        val nw = (vw * s).toInt(); val nh = (vh * s).toInt()
        if (lp.width != nw || lp.height != nh) { lp.width = nw; lp.height = nh; sv.layoutParams = lp }
    }

    private fun say(t: String) { runOnUiThread { msg.text = t; msg.visibility = View.VISIBLE } }

    private fun run(host: String, port: Int, pin: Int) {
        var codec: MediaCodec? = null
        try {
            val s = Socket()
            socket = s
            s.connect(InetSocketAddress(host, port), 5000)
            s.tcpNoDelay = true
            val out = java.io.DataOutputStream(s.getOutputStream())
            out.writeInt(0x4B435354); out.writeInt(pin); out.flush()
            val din = DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
            val ok = din.readUnsignedByte()
            if (ok != 1) { say("Wrong code. Go back and try again."); return }
            val w = din.readInt(); val h = din.readInt()
            runOnUiThread { vw = w; vh = h; fit() }
            surfaceReady.await()
            var c: MediaCodec? = null
            val info = MediaCodec.BufferInfo()
            var first = true
            var cw = w; var ch = h
            fun startDecoder() {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, cw, ch)
                if (Build.VERSION.SDK_INT >= 30) fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                val d = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                d.configure(fmt, sv.holder.surface, null, 0)
                d.start()
                c = d; codec = d
            }
            startDecoder()
            while (!stopped) {
                val size = din.readInt()
                val flag = din.readUnsignedByte()
                val pts = din.readLong()
                val data = ByteArray(size)
                din.readFully(data)
                if (flag == 3) {
                    val bb = java.nio.ByteBuffer.wrap(data)
                    cw = bb.getInt(); ch = bb.getInt()
                    try { c?.stop() } catch (e: Exception) { }
                    try { c?.release() } catch (e: Exception) { }
                    runOnUiThread { vw = cw; vh = ch; fit() }
                    startDecoder()
                    continue
                }
                val dec = c ?: break
                var idx = -1
                while (!stopped) {
                    idx = dec.dequeueInputBuffer(10_000)
                    if (idx >= 0) break
                    drain(dec, info)
                }
                if (idx < 0) break
                val ib = dec.getInputBuffer(idx)!!
                ib.clear(); ib.put(data)
                val fl = if (flag == 1) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else if (flag == 2) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                dec.queueInputBuffer(idx, 0, size, pts, fl)
                drain(dec, info)
                if (first && flag != 1) { first = false; runOnUiThread { msg.visibility = View.GONE } }
            }
        } catch (e: Exception) {
            if (!stopped) say("Disconnected (" + e.javaClass.simpleName + "). Go back to reconnect.")
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            try { codec?.release() } catch (e: Exception) { }
            try { socket?.close() } catch (e: Exception) { }
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o >= 0) c.releaseOutputBuffer(o, true) else break
        }
    }

    override fun onDestroy() {
        stopped = true
        try { socket?.close() } catch (e: Exception) { }
        super.onDestroy()
    }
}
