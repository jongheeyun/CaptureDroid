package com.example.capturedroid

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*

class ScreenCaptureService : Service() {
    private lateinit var mediaProjection: MediaProjection
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var imageReader: ImageReader
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra("resultCode") == true && intent.hasExtra("data")) {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")
            screenWidth = intent.getIntExtra("width", 0)
            screenHeight = intent.getIntExtra("height", 0)
            screenDensity = intent.getIntExtra("density", 0)

            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data!!)
            setupVirtualDisplay()
            startCapturing()
        }
        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )
    }

    private fun startCapturing() {
        scope.launch {
            while (true) {
                captureScreen()
                delay(60000 * 3) // 3분
            }
        }
    }

    private fun captureScreen() {
        try {
            val image = imageReader.acquireLatestImage()
            image?.use { img ->
                val planes = img.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                val timestamp = LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                )
                val file = File(getExternalFilesDir(null), "$timestamp.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 30, out)
                }
            }
        } catch (e: Exception) {
            Log.e("ScreenCapture", "Error capturing screen", e)
        }
    }

    private fun createNotification(): Notification {
        val channelId = "screen_capture_service"
        val channelName = "Screen Capture Service"
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        return Notification.Builder(this, channelId)
            .setContentTitle("Screen Capture")
            .setContentText("Capturing screen every 10 seconds")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }


    override fun onDestroy() {
        job.cancel()
        if (::virtualDisplay.isInitialized) virtualDisplay.release()
        if (::mediaProjection.isInitialized) mediaProjection.stop()
        // 모든 캡처된 PNG 파일 삭제
        //deleteAllCapturedFiles()
        super.onDestroy()
    }

    private fun deleteAllCapturedFiles() {
        try {
            val externalFilesDir = getExternalFilesDir(null)
            if (externalFilesDir != null && externalFilesDir.exists()) {
                externalFilesDir.listFiles()?.filter {
                    it.extension.equals("png", ignoreCase = true) 
                }?.forEach { file ->
                    Log.d("FileCleanup", "파일 삭제 시도: ${file.absolutePath}")
                    if (file.delete()) {
                        Log.d("FileCleanup", "파일 삭제 성공: ${file.name}")
                    } else {
                        Log.e("FileCleanup", "파일 삭제 실패: ${file.name}")
                    }
                }
                Log.d("FileCleanup", "모든 캡처 파일이 삭제 되었습니다")
            } else {
                Log.e("FileCleanup", "외부 파일 디렉토리가 존재하지 않거나 접근할 수 없습니다")
            }
        } catch (e: Exception) {
            Log.e("FileCleanup", "파일 삭제 중 오류 발생: ${e.message}", e)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}