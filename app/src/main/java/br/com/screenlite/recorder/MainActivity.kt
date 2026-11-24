package br.com.screenlite.recorder

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import android.Manifest
import java.util.Locale

class MainActivity : Activity() {
    private val requestCapture = 410
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private var settingsPage = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
        if (intent.getBooleanExtra("openSettings", false)) showSettings() else showHome()
    }

    private fun base(title: String) {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(17,17,17)); setPadding(18,18,18,12) }
        val heading = TextView(this).apply { text = title; textSize = 27f; setTextColor(Color.WHITE); setPadding(4,16,4,20) }
        root.addView(heading)
        setContentView(root)
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text; setTextColor(Color.WHITE); setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(38,38,38)))
        setOnClickListener { action() }
    }

    // ---------- lista de vídeos ----------

    private fun fmtDur(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    private fun fmtSize(b: Long): String =
        if (b >= 1024L * 1024L * 1024L) String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        else String.format(Locale.US, "%d MB", b / 1048576L)

    private fun loadThumb(img: ImageView, uri: Uri) {
        Thread {
            try {
                val bmp = contentResolver.loadThumbnail(uri, android.util.Size(dp(220), dp(180)), null)
                runOnUiThread { if (!isDestroyed) img.setImageBitmap(bmp) }
            } catch (_: Exception) { }
        }.start()
    }

    private fun shareVideo(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
        send.type = "video/mp4"
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Compartilhar vídeo"))
    }

    private fun confirmDelete(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("Apagar vídeo?")
            .setMessage("Essa gravação será removida do aparelho.")
            .setPositiveButton("Apagar") { _, _ ->
                try { contentResolver.delete(uri, null, null) } catch (e: Exception) {
                    Toast.makeText(this, "Não foi possível apagar: ${e.message}", Toast.LENGTH_LONG).show()
                }
                showHome()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun videoCard(uri: Uri, name: String, durMs: Long, size: Long, w: Int, h: Int): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.rgb(34, 34, 34)); bg.cornerRadius = dp(12).toFloat()
        row.background = bg
        row.clipToOutline = true

        val thumb = ImageView(this)
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setBackgroundColor(Color.rgb(50, 50, 50))
        row.addView(thumb, LinearLayout.LayoutParams(dp(110), dp(90)))
        loadThumb(thumb, uri)

        val info = TextView(this)
        info.text = "$name\n${fmtDur(durMs)}  ·  ${w}x${h}\n${fmtSize(size)}"
        info.textSize = 14f; info.setTextColor(Color.LTGRAY)
        info.setPadding(dp(12), 0, dp(4), 0)
        row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val more = TextView(this)
        more.text = "⋮"; more.textSize = 24f; more.setTextColor(Color.WHITE)
        more.setPadding(dp(14), dp(10), dp(14), dp(10))
        more.setOnClickListener { anchor ->
            val menu = PopupMenu(this, anchor)
            menu.menu.add("Compartilhar")
            menu.menu.add("Apagar")
            menu.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Compartilhar" -> shareVideo(uri)
                    "Apagar" -> confirmDelete(uri)
                }
                true
            }
            menu.show()
        }
        row.addView(more)

        row.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        return row
    }

    private fun showHome() {
        settingsPage = false
        base("Vídeos")
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var count = 0
        var error: String? = null
        try {
            val collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ? OR ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(packageName, "Movies/ScreenLite%"))
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.Video.Media.DATE_ADDED} DESC")
                putInt("android:query-arg-match-pending", 1)
            }
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.IS_PENDING)
            contentResolver.query(collectionUri, projection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val videoId = c.getLong(0)
                    val itemUri = ContentUris.withAppendedId(collectionUri, videoId)
                    // Recupera gravações que ficaram marcadas como pendentes
                    if (c.getInt(6) == 1 && !RecorderService.isRecording) {
                        try { contentResolver.update(itemUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) } catch (_: Exception) { }
                    }
                    count++
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    lp.bottomMargin = dp(10)
                    list.addView(videoCard(itemUri, c.getString(1) ?: "Gravação", c.getLong(2), c.getLong(3), c.getInt(4), c.getInt(5)), lp)
                }
            }
        } catch (e: Exception) {
            error = e.toString()
        }
        if (count == 0) list.addView(TextView(this).apply { text = "Nenhum vídeo gravado ainda."; textSize = 16f; setTextColor(Color.GRAY); setPadding(8,28,8,28) })
        list.addView(TextView(this).apply {
            text = "ScreenLite build 5 · $count vídeo(s)" + (if (error != null) "\nErro: $error" else "")
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(8,16,8,16)
        })
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("●  Mostrar controle flutuante") { beginPermissionFlow() }, LinearLayout.LayoutParams(-1,-2))
        root.addView(button("⚙  Configurações") { showSettings() })
    }

    // ---------- permissões e captura ----------

    private fun beginPermissionFlow() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this,"Permita a sobreposição e toque novamente.",Toast.LENGTH_LONG).show(); return
        }
        val audio = prefs.getString("audio", "Nenhuma") ?: "Nenhuma"
        if (audio != "Nenhuma" && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 78); return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCapture)
    }

    @Deprecated("Deprecated by Android; kept for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode == requestCapture && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, RecorderService::class.java).apply {
                action = RecorderService.ACTION_PREPARE
                putExtra("resultCode",resultCode); putExtra("data",data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            Toast.makeText(this,"Controle flutuante ativado. Toque no botão vermelho para gravar.",Toast.LENGTH_LONG).show()
        }
    }

    // ---------- configurações ----------

    private fun showSettings() {
        settingsPage = true; base("Configurações")
        addChoice("Qualidade do vídeo", "quality", arrayOf("8 Mbps","12 Mbps","20 Mbps","30 Mbps","50 Mbps"), "20 Mbps")
        addChoice("Taxa de quadros", "fps", arrayOf("24 fps","30 fps","48 fps","60 fps"), "30 fps")
        addChoice("Resolução", "resolution", arrayOf("720p","1080p","1440p","Automática"), "Automática")
        addChoice("Fonte de áudio", "audio", arrayOf("Nenhuma","Microfone","Sistema","Ambos"), "Nenhuma")
        root.addView(TextView(this).apply {
            text = "Nota: Sistema grava o som do jogo, Microfone grava a sua voz e Ambos mistura os dois. Alguns jogos/apps podem bloquear a captura do áudio do sistema.\nAs configurações são aplicadas à próxima gravação."
            setTextColor(Color.LTGRAY); textSize = 13f; setPadding(4, 18, 4, 18)
        })
        root.addView(button("Voltar") { showHome() })
    }

    private fun addChoice(label: String, key: String, options: Array<String>, default: String) {
        val wrap = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(4,8,4,8) }
        wrap.addView(TextView(this).apply { text=label; textSize=17f; setTextColor(Color.WHITE) })
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        val stored = prefs.getString(key, default) ?: default
        val idx = options.indexOf(stored).let { if(it<0) 0 else it }
        spinner.setSelection(idx)
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.edit().putString(key, options[position]).apply() }
        }
        wrap.addView(spinner); root.addView(wrap)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("openSettings", false)) showSettings() else showHome()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 78 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) beginPermissionFlow()
    }

    override fun onResume() {
        super.onResume()
        if (!settingsPage && ::root.isInitialized) showHome()
    }
}
