package dev.karim.kcast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import kotlin.random.Random

class Frame(val flag: Int, val pts: Long, val data: ByteArray)

class Client(val socket: Socket) {
    val queue = LinkedBlockingQueue<Frame>(8)
    @Volatile var needKey = true
    @Volatile var alive = true
}

class CastService : Service() {
    companion object {
        const val ACTION_START = "dev.karim.kcast.START"
        const val ACTION_STOP = "dev.karim.kcast.STOP"
        const val PORT = 8554
        const val SERVICE_TYPE = "_kcast._tcp."
        @Volatile var running = false
        @Volatile var pin = ""
        @Volatile var viewers = 0
        @Volatile var error = ""
        @Volatile var preset = 0
        val maxDim = intArrayOf(720, 1024, 1280)
        val bitrates = intArrayOf(2_500_000, 4_000_000, 6_000_000)

        fun localIps(): List<String> {
            val out = ArrayList<String>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses) {
                        if (a is java.net.Inet4Address && !a.isLoopbackAddress) out.add(a.hostAddress ?: "")
                    }
                }
            } catch (e: Exception) { }
            return out
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var server: ServerSocket? = null
    private val clients = ArrayList<Client>()
    @Volatile private var config: Frame? = null
    @Volatile private var width = 0
    @Volatile private var height = 0
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var dispMgr: DisplayManager? = null
    private var dispListener: DisplayManager.DisplayListener? = null
    private var dpi = 320
    private val mainH = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        preset = (intent?.getIntExtra("preset", 0) ?: 0).coerceIn(0, 2)
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        startForegroundNow()
        try {
            startCasting(code, data)
        } catch (e: Exception) {
            error = "Could not start: " + e.javaClass.simpleName + " " + (e.message ?: "")
            shutdown()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("kcast", "k cast", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, CastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, "kcast")
            .setContentTitle("k cast is sharing your screen")
            .setContentText("Tap to open. Use Stop to end.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Stop", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun startCasting(code: Int, data: Intent) {
        error = ""
        pin = String.format("%04d", Random.nextInt(10000))
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Handler(Looper.getMainLooper()).post { shutdown(); stopSelf() } }
        }, Handler(Looper.getMainLooper()))

        val dmgr = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dispMgr = dmgr
        val size = realSize()
        dpi = resources.displayMetrics.densityDpi
        width = size.first; height = size.second
        val enc = makeEncoder(width, height)
        val surface = enc.createInputSurface()
        enc.start()
        encoder = enc
        display = proj.createVirtualDisplay("kcast", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        val dl = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) { if (id == 0) mainH.post { checkResize() } }
        }
        dispListener = dl
        dmgr.registerDisplayListener(dl, mainH)

        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(java.net.InetSocketAddress(PORT))
        server = ss
        running = true
        registerNsd()
        Thread({ acceptLoop(ss) }, "kcast-accept").start()
        Thread({ drainLoop(enc) }, "kcast-encode").start()
    }

    private fun realSize(): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        val d = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(0)
        @Suppress("DEPRECATION")
        d.getRealMetrics(dm)
        var w = dm.widthPixels
        var h = dm.heightPixels
        val scale = minOf(1.0, maxDim[preset].toDouble() / maxOf(w, h))
        w = ((w * scale).toInt() / 8) * 8
        h = ((h * scale).toInt() / 8) * 8
        return Pair(maxOf(w, 16), maxOf(h, 16))
    }

    private fun makeEncoder(w: Int, h: Int): MediaCodec {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrates[preset])
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
        try {
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val f2 = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            f2.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            f2.setInteger(MediaFormat.KEY_BIT_RATE, bitrates[preset])
            f2.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            f2.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            f2.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            f2.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            f2.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) f2.setInteger(MediaFormat.KEY_LATENCY, 1)
            e.configure(f2, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return e
        } catch (ex: Exception) {
            val e = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            e.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return e
        }
    }

    @Synchronized
    private fun checkResize() {
        if (!running) return
        val sz = realSize()
        if (sz.first == width && sz.second == height) return
        try {
            val old = encoder
            width = sz.first; height = sz.second
            val enc = makeEncoder(width, height)
            val surface = enc.createInputSurface()
            enc.start()
            encoder = enc
            config = null
            synchronized(clients) { for (c in clients) { c.queue.clear(); c.needKey = true } }
            val bb = java.nio.ByteBuffer.allocate(8).putInt(width).putInt(height)
            broadcast(Frame(3, 0, bb.array()))
            display?.resize(width, height, dpi)
            display?.surface = surface
            try { old?.stop() } catch (e: Exception) { }
            try { old?.release() } catch (e: Exception) { }
            Thread({ drainLoop(enc) }, "kcast-encode").start()
        } catch (e: Exception) {
            error = "Rotation failed: " + e.javaClass.simpleName
        }
    }

    private fun registerNsd() {
        try {
            val info = NsdServiceInfo()
            info.serviceName = "kcast-" + Build.MODEL.replace(" ", "_")
            info.serviceType = SERVICE_TYPE
            info.port = PORT
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, c: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, c: Int) {}
            }
            val m = getSystemService(Context.NSD_SERVICE) as NsdManager
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m; regListener = l
        } catch (e: Exception) { }
    }

    private fun requestKey() {
        try {
            val b = Bundle()
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder?.setParameters(b)
        } catch (e: Exception) { }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try { ss.accept() } catch (e: Exception) { break }
            Thread({ handle(s) }, "kcast-client").start()
        }
    }

    private fun handle(s: Socket) {
        val c = Client(s)
        try {
            s.tcpNoDelay = true
            try { s.sendBufferSize = 128 * 1024 } catch (e: Exception) { }
            s.soTimeout = 5000
            val din = DataInputStream(s.getInputStream())
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 65536))
            val magic = din.readInt()
            val given = din.readInt()
            if (magic != 0x4B435354 || given.toString().padStart(4, '0') != pin) {
                out.writeByte(0); out.flush(); s.close(); return
            }
            out.writeByte(1)
            out.writeInt(width); out.writeInt(height)
            out.flush()
            s.soTimeout = 0
            config?.let { c.queue.offer(it) }
            synchronized(clients) { clients.add(c); viewers = clients.size }
            requestKey()
            while (c.alive && running) {
                val f = c.queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                out.writeInt(f.data.size); out.writeByte(f.flag); out.writeLong(f.pts)
                out.write(f.data)
                out.flush()
            }
        } catch (e: Exception) {
        } finally {
            c.alive = false
            try { s.close() } catch (e: Exception) { }
            synchronized(clients) { clients.remove(c); viewers = clients.size }
        }
    }

    private fun drainLoop(enc: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running && enc === encoder) {
            val i = try { enc.dequeueOutputBuffer(info, 100_000) } catch (e: Exception) { break }
            if (i < 0) continue
            val buf = enc.getOutputBuffer(i)
            if (buf != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(bytes)
                val flag = if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) 1
                    else if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) 2 else 0
                val f = Frame(flag, info.presentationTimeUs, bytes)
                if (flag == 1) { config = f; broadcast(f) } else broadcast(f)
            }
            try { enc.releaseOutputBuffer(i, false) } catch (e: Exception) { break }
        }
    }

    private fun broadcast(f: Frame) {
        var needSync = false
        synchronized(clients) {
            for (c in clients) {
                if (!c.alive) continue
                if (f.flag == 2) c.needKey = false else if (f.flag == 0 && c.needKey) continue
                if (!c.queue.offer(f)) { c.queue.clear(); c.needKey = true; needSync = true }
            }
        }
        if (needSync) requestKey()
    }

    @Synchronized
    private fun shutdown() {
        val was = running
        running = false
        viewers = 0
        try { server?.close() } catch (e: Exception) { }
        server = null
        synchronized(clients) { for (c in clients) { c.alive = false; try { c.socket.close() } catch (e: Exception) { } }; clients.clear() }
        try { display?.release() } catch (e: Exception) { }
        display = null
        try { encoder?.stop() } catch (e: Exception) { }
        try { encoder?.release() } catch (e: Exception) { }
        encoder = null
        try { dispListener?.let { dispMgr?.unregisterDisplayListener(it) } } catch (e: Exception) { }
        dispListener = null
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
        try { regListener?.let { nsd?.unregisterService(it) } } catch (e: Exception) { }
        regListener = null
        config = null
        if (was) pin = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() { shutdown(); super.onDestroy() }
}
