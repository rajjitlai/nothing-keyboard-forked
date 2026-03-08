package org.futo.inputmethod.latin.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.hardware.display.DisplayManager

class CaptureService : Service() {
    companion object {
        const val ACTION_SET_PROJECTION = "ACTION_SET_PROJECTION"
        const val ACTION_CAPTURE_ONCE = "ACTION_CAPTURE_ONCE"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_INTENT = "EXTRA_RESULT_INTENT"
        const val BROADCAST_SCREENSHOT = "org.futo.inputmethod.SCREENSHOT_READY"
        const val EXTRA_SCREENSHOT_BYTES = "screenshotBytes"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
    }

    private var mediaProjection: MediaProjection? = null
    private lateinit var mpManager: MediaProjectionManager
    private var imageReader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var mpCallback: MediaProjection.Callback? = null
    private val handlerThread = HandlerThread("capture-thread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    override fun onCreate() {
        super.onCreate()
        mpManager = getSystemService(MediaProjectionManager::class.java)
        startForegroundIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("CaptureService", "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_SET_PROJECTION -> {
                val rc = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_INTENT)
                Log.i("CaptureService", "ACTION_SET_PROJECTION rc=$rc data=${data != null}")
                if (rc == android.app.Activity.RESULT_OK && data != null) {
                    mediaProjection = mpManager.getMediaProjection(rc, data)
                    // Register a callback to manage projection lifecycle as required by newer APIs.
                    try {
                        val callback = object : MediaProjection.Callback() {
                            override fun onStop() {
                                Log.i("CaptureService", "MediaProjection stopped by system")
                                stopSelf()
                            }
                        }
                        mpCallback = callback
                        mediaProjection?.registerCallback(callback, handler)
                    } catch (e: Exception) {
                        Log.w("CaptureService", "Failed to register MediaProjection callback: ${e.message}")
                    }
                }
                // pass-through target package if any
                intent.getStringExtra(EXTRA_TARGET_PACKAGE)?.let {
                    // store as tag on service via intent; not persisted beyond this command
                }
            }
            ACTION_CAPTURE_ONCE -> captureOnce(intent?.getStringExtra(EXTRA_TARGET_PACKAGE))
        }
        return START_NOT_STICKY
    }

    private fun captureOnce(targetPackage: String?) {
        if (mediaProjection == null) {
            Log.w("CaptureService", "No MediaProjection available - aborting captureOnce")
            return
        }

        val metrics = Resources.getSystem().displayMetrics
        val density = metrics.densityDpi
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        Log.i("CaptureService", "captureOnce width=${width} height=${height} density=${density}")

        imageReader?.close()
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay?.release()
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "screencap",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        handler.post {
            SystemClock.sleep(200)
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                val baos = java.io.ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
                val bytes = baos.toByteArray()
                Log.i("CaptureService", "captured image bytes=${bytes.size}, broadcasting")
                val b = Intent(BROADCAST_SCREENSHOT).apply {
                    putExtra(EXTRA_SCREENSHOT_BYTES, bytes)
                    putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
                }
                // Send an explicit broadcast to our own package to avoid OEM broadcast filtering
                try {
                    b.setPackage(packageName)
                    sendBroadcast(b)
                    Log.i("CaptureService", "broadcast sent explicitly to package=${packageName}")
                } catch (e: Exception) {
                    Log.w("CaptureService", "explicit broadcast failed: ${e.message}, sending normally")
                    sendBroadcast(b)
                }
            } else {
                Log.w("CaptureService", "No image acquired")
            }
            stopSelf()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bmp = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
    }

    private fun startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channelId = "capture_service"
            val channel = NotificationChannel(channelId, "Capture Service", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
            val n = Notification.Builder(this, channelId).setContentTitle("Capture service").setContentText("Ready").build()
            startForeground(12345, n)
        } else {
            val n = Notification()
            startForeground(12345, n)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handlerThread.quitSafely()
        try {
            mpCallback?.let { cb -> mediaProjection?.unregisterCallback(cb) }
        } catch (e: Exception) { /* ignore */ }
        mediaProjection?.stop()
        super.onDestroy()
    }
}
