package com.example.capturedroid

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fi.iki.elonen.NanoHTTPD
import android.util.Log
import android.widget.TextView
import java.net.NetworkInterface
import java.util.Collections
import android.media.projection.MediaProjectionManager
import android.app.Activity
import android.content.Intent
import android.content.Context
import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var webServer: SimpleWebServer
    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Update TextView with IP address
        val ipAddress = getIPAddress()
        findViewById<TextView>(R.id.text_message).text = "$ipAddress:3333"

        // Start web server
        startWebServer()

        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startScreenCapture()
    }

    private fun getIPAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.indexOf(':') == -1) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("IP Address", "Error getting IP address", e)
        }
        return "Unknown"
    }

    private fun startWebServer() {
        webServer = SimpleWebServer()
        try {
            webServer.start()
            Log.d("WebServer", "Server started on port 3333")
        } catch (e: Exception) {
            Log.e("WebServer", "Error starting server: ${e.message}")
        }
    }

    private fun startScreenCapture() {
        val intent = projectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, SCREEN_CAPTURE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("width", resources.displayMetrics.widthPixels)
                putExtra("height", resources.displayMetrics.heightPixels)
                putExtra("density", resources.displayMetrics.densityDpi)
            }
            startForegroundService(serviceIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::webServer.isInitialized) {
            webServer.stop()
        }
        // 서비스 중지
        stopService(Intent(this, ScreenCaptureService::class.java))
        // PNG 파일 삭제
        deleteAllCapturedFiles()
    }

    private fun deleteAllCapturedFiles() {
        try {
            val externalFilesDir = getExternalFilesDir(null)
            if (externalFilesDir != null && externalFilesDir.exists()) {
                externalFilesDir.listFiles()?.filter {
                    it.extension.equals("png", ignoreCase = true) 
                }?.forEach { file ->
                    Log.d("FileCleanup", "파일 삭제 시도 (MainActivity): ${file.absolutePath}")
                    if (file.delete()) {
                        Log.d("FileCleanup", "파일 삭제 성공 (MainActivity): ${file.name}")
                    } else {
                        Log.e("FileCleanup", "파일 삭제 실패 (MainActivity): ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FileCleanup", "파일 삭제 중 오류 발생 (MainActivity)", e)
        }
    }

    private inner class SimpleWebServer : NanoHTTPD(3333) {
        override fun serve(session: IHTTPSession): Response {
            return when (session.uri) {
                "/" -> serveFileList()
                else -> serveFile(session.uri.substring(1))
            }
        }

        private fun serveFileList(): Response {
            val files = getExternalFilesDir(null)?.listFiles()?.filter { it.extension == "png" }
            val html = StringBuilder()
                .append("<html><body><h3>Good Morning</h3><ul>")
                
            files?.forEach { file ->
                html.append("<li><a href='${file.name}'>${file.name}</a></li>")
            }
            
            html.append("</ul></body></html>")
            return newFixedLengthResponse(html.toString())
        }

        private fun serveFile(fileName: String): Response {
            val file = File(getExternalFilesDir(null), fileName)
            return if (file.exists()) {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/png",
                    FileInputStream(file),
                    file.length()
                )
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
            }
        }
    }

    companion object {
        private const val SCREEN_CAPTURE_REQUEST_CODE = 100
    }
}