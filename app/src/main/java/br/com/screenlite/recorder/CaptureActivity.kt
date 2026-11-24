package br.com.screenlite.recorder

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle

/** Activity transparente: pede a autorização de captura e devolve para o serviço, que começa a gravar. */
class CaptureActivity : Activity() {
    private val requestCapture = 411

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), requestCapture)
    }

    @Deprecated("Deprecated by Android; kept for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture && resultCode == RESULT_OK && data != null) {
            val service = Intent(this, RecorderService::class.java).apply {
                action = RecorderService.ACTION_PREPARE
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("autoStart", true)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        }
        finish()
    }
}
