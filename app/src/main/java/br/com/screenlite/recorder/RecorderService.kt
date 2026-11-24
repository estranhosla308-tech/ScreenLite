package br.com.screenlite.recorder

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
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
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputUri: Uri? = null
    private var outputPfd: android.os.ParcelFileDescriptor? = null
    private var recording: Boolean
        get() = isRecording
        set(v) { isRecording = v }
    private var timerHandler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var timer: TextView? = null
    private var recordButton: TextView? = null

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
        val selectedAudio = getSharedPreferences("settings", MODE_PRIVATE).getString("audio", "Nenhuma") ?: "Nenhuma"
        var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (Build.VERSION.SDK_INT >= 34 && (selectedAudio == "Microfone" || selectedAudio == "Ambos")) {
            serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, serviceTypes)
        else startForeground(NOTIFICATION_ID, notification)
        try {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = projectionData(intent)
            if (data == null) { stopSelf(); return }
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            // Android 14+ exige um callback registrado antes de criar o VirtualDisplay
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection != null) { projection = null; closeFloating() }
                }
            }, Handler(Looper.getMainLooper()))
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            showFloating()
        } catch (e: Exception) {
            Toast.makeText(this, "Não foi possível iniciar: ${e.message}", Toast.LENGTH_LONG).show(); stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33)
        intent.getParcelableExtra("data", Intent::class.java) else intent.getParcelableExtra("data")

    private fun showFloating() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8); setBackgroundColor(Color.rgb(25, 38, 53))
        }
        fun control(text: String, color: Int, action: () -> Unit): TextView = TextView(this).apply {
            this.text=text; textSize=18f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
            setBackgroundColor(color); setPadding(15,13,15,13); setOnClickListener { action() }
        }
        recordButton = control("●", Color.rgb(220,35,45)) { if (recording) stopRecording() else startRecording() }
        panel.addView(recordButton)
        timer = TextView(this).apply { text="00:00"; textSize=17f; setTextColor(Color.WHITE); setPadding(10,0,10,0) }
        panel.addView(timer)
        panel.addView(control("▣", Color.rgb(45,65,85)) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) })
        panel.addView(control("⚙", Color.rgb(45,65,85)) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("openSettings", true)) })
        panel.addView(control("×", Color.rgb(65,45,45)) { closeFloating() })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL; y=120 }
        try { floating=panel; windowManager.addView(panel,params) } catch (_: Exception) { stopSelf() }
    }


    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun startRecording() {
        if (recording) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val audio = prefs.getString("audio", "Nenhuma") ?: "Nenhuma"
        if (audio == "Sistema" || audio == "Ambos") {
            Toast.makeText(this, "Áudio interno ainda não está disponível nesta versão. Escolha Microfone ou Nenhuma.", Toast.LENGTH_LONG).show(); return
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
            recorder = createRecorder()
            recorder!!.apply {
                if (audio == "Microfone") setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (audio == "Microfone") setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width.coerceAtLeast(2), height.coerceAtLeast(2))
                setVideoFrameRate(fps)
                setVideoEncodingBitRate(bitrate)
                if (audio == "Microfone") setAudioEncodingBitRate(128_000)
                setOutputFile(outputPfd!!.fileDescriptor)
                prepare()
            }
            val surface = recorder!!.surface
            val density = metrics.densityDpi
            virtualDisplay = projection?.createVirtualDisplay("ScreenLiteCapture", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
                ?: throw IllegalStateException("A sessão de captura expirou")
            recorder!!.start()
            recording=true; startedAt=System.currentTimeMillis(); recordButton?.text="■"; recordButton?.setBackgroundColor(Color.rgb(230,75,35))
            timerHandler.post(timerTick)
        } catch (e: Exception) {
            Toast.makeText(this,"Erro ao gravar: ${e.message}",Toast.LENGTH_LONG).show(); releaseRecorder(deleteFile=true)
        }
    }

    private val timerTick = object : Runnable {
        override fun run() {
            if (!recording) return
            val seconds=(System.currentTimeMillis()-startedAt)/1000
            timer?.text=String.format(Locale.US,"%02d:%02d",seconds/60,seconds%60)
            timerHandler.postDelayed(this,500)
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording=false; timerHandler.removeCallbacks(timerTick); val savedUri = outputUri
        try { recorder?.stop() } catch (_: Exception) { }
        releaseRecorder(deleteFile=false)
        try { savedUri?.let { uri ->
            val values=ContentValues().apply { if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING,0) }
            contentResolver.update(uri,values,null,null)
        } } catch (_: Exception) { }
        recordButton?.text="●"; recordButton?.setBackgroundColor(Color.rgb(220,35,45)); timer?.text="Salvo"
        Toast.makeText(this,"Gravação salva em Vídeos/ScreenLite",Toast.LENGTH_LONG).show()
        // Android 14+ requires a fresh consent token and a new virtual display for every session.
        val p = projection; projection = null; try { p?.stop() } catch (_: Exception) { }
        timerHandler.postDelayed({ closeFloating() }, 1400)
    }

    private fun releaseRecorder(deleteFile: Boolean) {
        try { virtualDisplay?.release() } catch (_: Exception) { }; virtualDisplay=null
        try { recorder?.reset(); recorder?.release() } catch (_: Exception) { }; recorder=null
        try { outputPfd?.close() } catch (_: Exception) { }; outputPfd=null
        if (deleteFile) { try { outputUri?.let { contentResolver.delete(it,null,null) } } catch (_: Exception) { } }
        outputUri=null
    }

    private fun closeFloating() {
        if (recording) stopRecording()
        try { floating?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        floating=null
        if (!recording) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel=NotificationChannel(CHANNEL,"Gravação de tela",NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        timerHandler.removeCallbacksAndMessages(null)
        if (recording) { try { recorder?.stop() } catch (_: Exception) { }; recording=false }
        releaseRecorder(deleteFile=false)
        try { projection?.stop() } catch (_: Exception) { }
        try { floating?.let { windowManager.removeView(it) } } catch (_: Exception) { }
        super.onDestroy()
    }
}
