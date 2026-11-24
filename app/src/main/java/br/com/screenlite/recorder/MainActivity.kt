package br.com.screenlite.recorder

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.*
import android.Manifest

class MainActivity : Activity() {
    private val requestCapture = 410
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private var settingsPage = false

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
                    val name = c.getString(1) ?: "Gravação"
                    val duration = (c.getLong(2) / 1000).coerceAtLeast(0)
                    val size = c.getLong(3) / (1024 * 1024)
                    val w = c.getInt(4); val h = c.getInt(5)
                    val card = TextView(this).apply {
                        text = "$name\nDuração: ${duration/60}:${(duration%60).toString().padStart(2,'0')}\nResolução: ${w}x${h}\nTamanho: ${size} MB"
                        textSize = 16f; setTextColor(Color.LTGRAY); setPadding(18,18,18,18); setBackgroundColor(Color.rgb(34,34,34))
                    }
                    val lp = LinearLayout.LayoutParams(-1,-2); lp.bottomMargin = 12; list.addView(card,lp)
                    card.setOnClickListener {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(itemUri,"video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                }
            }
        } catch (e: Exception) {
            error = e.toString()
        }
        if (count == 0) list.addView(TextView(this).apply { text="Nenhum vídeo gravado ainda."; textSize=16f; setTextColor(Color.GRAY); setPadding(8,28,8,28) })
        list.addView(TextView(this).apply {
            text = "ScreenLite build 4 · $count vídeo(s)" + (if (error != null) "\nErro: $error" else "")
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(8,16,8,16)
        })
        scroll.addView(list); root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("●  Mostrar controle flutuante") { beginPermissionFlow() }, LinearLayout.LayoutParams(-1,-2))
        root.addView(button("⚙  Configurações") { showSettings() })
    }

    private fun beginPermissionFlow() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Toast.makeText(this,"Permita a sobreposição e toque novamente.",Toast.LENGTH_LONG).show(); return
        }
        val audio = prefs.getString("audio", "Nenhuma") ?: "Nenhuma"
        if (audio == "Sistema" && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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

    private fun showSettings() {
        settingsPage = true; base("Configurações")
        addChoice("Qualidade do vídeo", "quality", arrayOf("8 Mbps","12 Mbps","20 Mbps","30 Mbps","50 Mbps"), "20 Mbps")
        addChoice("Taxa de quadros", "fps", arrayOf("24 fps","30 fps","48 fps","60 fps"), "30 fps")
        addChoice("Resolução", "resolution", arrayOf("720p","1080p","1440p","Automática"), "Automática")
        addChoice("Fonte de áudio", "audio", arrayOf("Nenhuma","Microfone","Sistema","Ambos"), "Nenhuma")
        root.addView(TextView(this).apply {
            text = "Nota: por enquanto só Sistema e Nenhuma funcionam. Alguns jogos/apps podem bloquear a captura do áudio do sistema.\nAs configurações são aplicadas à próxima gravação."
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
