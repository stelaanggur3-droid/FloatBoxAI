package com.floatbox.ai

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val captured = AtomicBoolean(false)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForegroundMediaProjection()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.let {
            IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            notifyCancelledAndStop()
            return START_NOT_STICKY
        }

        handler.postDelayed({
            startCapture(resultCode, resultData)
        }, 380)

        handler.postDelayed({
            if (!captured.get()) {
                notifyCancelledAndStop()
            }
        }, 4000)

        return START_NOT_STICKY
    }

    private fun startForegroundMediaProjection() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_floatbox_logo)
            .setContentTitle("FloatBox AI Memindai Layar")
            .setContentText("Menangkap tampilan layar untuk OCR lokal...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FloatBox Screen Scan",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                notifyCancelledAndStop()
                return
            }
            mediaProjection = projection

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanup()
                }
            }, handler)

            val (width, height, densityDpi) = getRealScreenMetrics()
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            reader.setOnImageAvailableListener({ r ->
                if (!captured.compareAndSet(false, true)) return@setOnImageAvailableListener
                val image = r.acquireLatestImage()
                if (image == null) {
                    captured.set(false)
                    return@setOnImageAvailableListener
                }

                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val tempBitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        Bitmap.Config.ARGB_8888
                    )
                    tempBitmap.copyPixelsFromBuffer(buffer)
                    val finalBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
                    if (tempBitmap != finalBitmap) {
                        tempBitmap.recycle()
                    }

                    val outFile = File(cacheDir, SCAN_FILE_NAME)
                    FileOutputStream(outFile).use { fos ->
                        finalBitmap.compress(Bitmap.CompressFormat.PNG, 95, fos)
                    }
                    finalBitmap.recycle()

                    val doneIntent = Intent(this, FloatingOverlayService::class.java).apply {
                        action = FloatingOverlayService.ACTION_SCREEN_CAPTURED
                    }
                    startService(doneIntent)
                } catch (_: Exception) {
                    notifyCancelledAndStop()
                } finally {
                    image.close()
                    cleanup()
                    stopSelf()
                }
            }, handler)

            virtualDisplay = projection.createVirtualDisplay(
                "FloatBoxScreenScan",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
        } catch (_: Exception) {
            notifyCancelledAndStop()
        }
    }

    private fun notifyCancelledAndStop() {
        val cancelIntent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_SCREEN_CAPTURE_CANCELLED
        }
        startService(cancelIntent)
        cleanup()
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun getRealScreenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val densityDpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), densityDpi)
        } else {
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
    }

    private fun cleanup() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
        } catch (_: Exception) {
        }
        try {
            imageReader?.close()
            imageReader = null
        } catch (_: Exception) {
        }
        try {
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cleanup()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "floatbox_screen_scan_channel"
        private const val NOTIFICATION_ID = 1002
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val SCAN_FILE_NAME = "last_screen_scan.png"
    }
}