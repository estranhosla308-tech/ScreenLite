package br.com.screenlite.recorder

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class RecorderService : Service() {
    companion object {
        const val ACTION_PREPARE = "br.com.screenlite.PREPARE"
        private const val CHANNEL = "screenlite_recording"
        private const val NOTIFICATION_ID = 901
        @Volatile var isRecording = false
    }

    private lateinit var windowManager: WindowManager
    private var floating: View? = null
    private var pill: DragPill? = null
    private var recBtn: IconButton? = null
    private var folderBtn: IconButton? = null
    private var gearBtn: IconButton? = null
    private var closeBtn: IconButton? = null
    private var timerView: TextView? = null
    private var projection: MediaProjection? = null
    private var session: SessionRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputUri: Uri? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null
    private var recording: Boolean
        get() = isRecording
        set(v) { isRecording = v }
    private var timerHandler = Handler(Looper.getMainLooper())
    private var startedAt = 0L

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PREPARE) prepareProjection(intent)
        return START_NOT_STICKY
    }

    private fun prepareProjection(intent: Intent) {
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("ScreenLite pronto")
            .setContentText("O controle flutuante está ativo")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true).build()
        var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        // Para usar o microfone com a tela bloqueada/em segundo plano (jogo aberto), o serviço precisa do tipo microfone
        if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, serviceTypes)
        else startForeground(NOTIFICATION_ID, notification)
        try {
            val auto = intent.getBooleanExtra("autoStart", false)
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = projectionData(intent)
            if (data == null) { if (floating == null) stopSelf(); return }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = manager.getMediaProjection(resultCode, data)
            projection = mp
            // Android 14+ exige um callback registrado antes de criar o VirtualDisplay
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection === mp) { projection = null; if (recording) stopRecording() }
                }
            }, Handler(Looper.getMainLooper()))
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            if (floating == null) showFloating()
            if (auto) timerHandler.postDelayed({ if (!recording) startRecording() }, 600)
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível iniciar: ${e.message}", Toast.LENGTH_LONG).show()
            if (floating == null) stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33)
        intent.getParcelableExtra("data", Intent::class.java) else intent.getParcelableExtra("data")

    // ---------- flutuante ----------

    private fun showFloating() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = maxOf(0, (resources.displayMetrics.widthPixels - dp(200)) / 2)
            y = dp(48)
        }
        val p = DragPill(this, windowManager, params)
        p.orientation = LinearLayout.HORIZONTAL
        p.gravity = Gravity.CENTER_VERTICAL
        p.setPadding(dp(8), dp(6), dp(10), dp(6))
        p.alpha = 0.95f

        val rec = IconButton(this, IconButton.REC)
        rec.setOnClickListener { onRecordTapped() }
        p.addView(rec, LinearLayout.LayoutParams(dp(46), dp(46)))

        val tv = TextView(this)
        tv.text = "00:00"; tv.textSize = 20f; tv.setTextColor(Color.WHITE)
        tv.setPadding(dp(10), 0, dp(14), 0); tv.visibility = View.GONE
        p.addView(tv)

        fun icon(kind: Int, action: () -> Unit): IconButton {
            val b = IconButton(this, kind)
            b.setOnClickListener { action() }
            val lpIcon = LinearLayout.LayoutParams(dp(38), dp(38))
            lpIcon.leftMargin = dp(6)
            p.addView(b, lpIcon)
            return b
        }
        folderBtn = icon(IconButton.FOLDER) { openApp(false) }
        gearBtn = icon(IconButton.GEAR) { openApp(true) }
        closeBtn = icon(IconButton.CLOSE) { closeFloating() }

        pill = p; recBtn = rec; timerView = tv; floating = p
        styleIdle()
        try { windowManager.addView(p, params) } catch (_: Exception) { stopSelf() }
    }

    private fun styleIdle() {
        val bg = GradientDrawable()
        bg.setColor(0xFF1C2C38.toInt()); bg.cornerRadius = dp(40).toFloat()
        pill?.background = bg
        recBtn?.recording = false
        pill?.alpha = 0.95f
        timerView?.visibility = View.GONE
        folderBtn?.visibility = View.VISIBLE
        gearBtn?.visibility = View.VISIBLE
        closeBtn?.visibility = View.VISIBLE
    }

    private fun styleRecording() {
        val bg = GradientDrawable()
        bg.setColor(0xFF2E2E30.toInt()); bg.cornerRadius = dp(40).toFloat()
        bg.setStroke(dp(1), 0xFF4A4A4D.toInt())
        pill?.background = bg
        recBtn?.recording = true
        pill?.alpha = 0.5f
        timerView?.text = "00:00"
        timerView?.visibility = View.VISIBLE
        folderBtn?.visibility = View.GONE
        gearBtn?.visibility = View.GONE
        closeBtn?.visibility = View.GONE
    }

    private fun openApp(settings: Boolean) {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (settings) i.putExtra("openSettings", true)
        startActivity(i)
    }

    private fun onRecordTapped() {
        if (recording) { stopRecording(); return }
        if (projection == null) {
            // Android 14+: cada gravação precisa de uma nova autorização
            val i = Intent(this, CaptureActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } else {
            startRecording()
        }
    }

    // ---------- gravação ----------

    private fun startRecording() {
        if (recording) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val audio = prefs.getString("audio", "Nenhuma") ?: "Nenhuma"
        val useSys = audio == "Sistema" || audio == "Ambos"
        val useMic = audio == "Microfone" || audio == "Ambos"
        if ((useSys || useMic) && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permita o acesso de áudio ao app (abra o app e toque em Mostrar controle flutuante).", Toast.LENGTH_LONG).show(); return
        }
        try {
            val metrics = resources.displayMetrics
            val rawW = metrics.widthPixels; val rawH = metrics.heightPixels
            val resolution = prefs.getString("resolution", "Automática") ?: "Automática"
            val scale = when(resolution) { "720p" -> 720f / maxOf(rawW,rawH); "1080p" -> 1080f / maxOf(rawW,rawH); "1440p" -> 1440f / maxOf(rawW,rawH); else -> 1f }
            val width = ((rawW * scale).toInt() / 2) * 2
            val height = ((rawH * scale).toInt() / 2) * 2
            val fps = (prefs.getString("fps", "30 fps") ?: "30 fps").substringBefore(" ").toIntOrNull() ?: 30
            val bitrate = (prefs.getString("quality", "20 Mbps") ?: "20 Mbps").substringBefore(" ").toIntOrNull()?.times(1_000_000) ?: 20_000_000
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "ScreenLite_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenLite")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Não foi possível criar o arquivo de vídeo")
            outputPfd = contentResolver.openFileDescriptor(outputUri!!, "w") ?: throw IllegalStateException("Falha ao abrir arquivo")
            val proj = projection ?: throw IllegalStateException("A sessão de captura expirou")
            val rec = SessionRecorder(proj, outputPfd!!.fileDescriptor, width.coerceAtLeast(2), height.coerceAtLeast(2), fps, bitrate, useSys, useMic)
            session = rec
            rec.prepare()
            val density = metrics.densityDpi
            virtualDisplay = proj.createVirtualDisplay("ScreenLiteCapture", width.coerceAtLeast(2), height.coerceAtLeast(2), density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rec.inputSurface, null, null)
            rec.start()
            recording = true; startedAt = System.currentTimeMillis()
            styleRecording()
            timerHandler.post(timerTick)
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao gravar: ${e.message}", Toast.LENGTH_LONG).show()
            releaseRecorder(deleteFile = true)
            styleIdle()
        }
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val seconds = (System.currentTimeMillis() - startedAt) / 1000
            timerView?.text = String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
            timerHandler.postDelayed(this, 500)
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false; timerHandler.removeCallbacks(timerTick); val savedUri = outputUri
        releaseRecorder(deleteFile = false)
        try { savedUri?.let { uri ->
            val values = ContentValues().apply { if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 0) }
            contentResolver.update(uri, values, null, null)
        } } catch (_: Exception) { }
        styleIdle()
        Toast.makeText(this, "Gravação salva em Vídeos/ScreenLite", Toast.LENGTH_LONG).show()
        // Android 14+: nova autorização e novo VirtualDisplay a cada gravação (o flutuante continua na tela).
        val p = projection; projection = null; try { p?.stop() } catch (_: Exception) { }
    }

    private fun releaseRecorder(deleteFile: Boolean) {
        try { virtualDisplay?.release() } catch (_: Exception) { }; virtualDisplay = null
        try { session?.stop() } catch (_: Exception) { }; session = null
        try { outputPfd?.close() } catch (_: Exception) { }; outputPfd = null
        if (deleteFile) { try { outputUri?.let { contentResolver.delete(it, null, null) } } catch (_: Exception) { } }
        outputUri = null
    }

    private fun closeFloating() {
        if (recording) stopRecording()
        try { floating?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        floating = null; pill = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL, "Gravação de tela", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null)
        recording = false
        releaseRecorder(deleteFile = false)
        try { projection?.stop() } catch (_: Exception) { }
        try { floating?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        super.onDestroy()
    }
}
