package org.futo.inputmethod.latin.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.app.ActivityOptions

class CapturePermissionActivity : Activity() {
    companion object {
        const val REQ_CODE = 1001
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
    }

    private lateinit var mpManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("CapturePermissionAct", "onCreate: launching media projection intent")
        mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val permissionIntent = mpManager.createScreenCaptureIntent()
        Log.i("CapturePermissionAct", "created permission intent")
        // launch the system capture permission dialog
        startActivityForResult(permissionIntent, REQ_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Log.i("CapturePermissionAct", "onActivityResult: requestCode=$requestCode resultCode=$resultCode data=${data != null}")
        if (requestCode == REQ_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val svc = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_SET_PROJECTION
                    putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_RESULT_INTENT, data)
                    putExtra(EXTRA_TARGET_PACKAGE, intent?.getStringExtra(EXTRA_TARGET_PACKAGE))
                }
                Log.i("CapturePermissionAct", "starting CaptureService with projection result")
                startForegroundService(svc)
                // Immediately request a capture
                val captureNow = Intent(this, CaptureService::class.java).apply { action = CaptureService.ACTION_CAPTURE_ONCE }
                startService(captureNow)
            }
        }
        finish()
    }
}
