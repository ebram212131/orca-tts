package com.orca.tts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

// ── WebSocket Server ──────────────────────────────────────────────

class TTSServer(
    context: Context,
    port: Int = 3457,
    private val onStatus: (String) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            onStatus(if (ttsReady) "TTS Ready — port $port" else "TTS init failed")
        }
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d("TTS", "Client connected")
        onStatus("Client connected")
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d("TTS", "Client disconnected")
        onStatus("Waiting for client...")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {}

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        message ?: return
        try {
            val data = ByteArray(message.remaining())
            message.get(data)
            parseAndHandle(data)
        } catch (e: Exception) {
            Log.e("TTS", "Error: ${e.message}")
        }
    }

    private fun parseAndHandle(data: ByteArray) {
        if (data.isEmpty()) return
        val firstByte = data[0].toInt() and 0xFF
        val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(data)

        when {
            // Fixstr or str8/16/32 → "CancelSpeech"
            firstByte == 0xd9 || firstByte == 0xda || firstByte == 0xdb ||
            (firstByte in 0xa0..0xbf) -> {
                val cmd = unpacker.unpackString()
                Log.d("TTS", "Cmd: $cmd")
                if (cmd == "CancelSpeech") tts?.stop()
            }
            // Fixmap or map8/16/32 → {"SpeakText": "text"}
            firstByte == 0xde || firstByte == 0xdf ||
            (firstByte in 0x80..0x8f) -> {
                val size = unpacker.unpackMapHeader()
                if (size >= 1) {
                    val key = unpacker.unpackString()
                    when (key) {
                        "SpeakText" -> {
                            val text = unpacker.unpackString()
                            Log.d("TTS", "Speak: $text")
                            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "tts")
                        }
                        "BrailleMessage" -> unpacker.unpackString()
                    }
                }
            }
            // Fixarray → ["SpeakText", "text"]
            firstByte == 0xdc || firstByte == 0xdd ||
            (firstByte in 0x90..0x9f) -> {
                val size = unpacker.unpackArrayHeader()
                if (size >= 1) {
                    val cmd = unpacker.unpackString()
                    when (cmd) {
                        "SpeakText" -> if (size >= 2) {
                            val text = unpacker.unpackString()
                            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "tts")
                        }
                        "CancelSpeech" -> tts?.stop()
                    }
                }
            }
        }
        unpacker.close()
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("TTS", "WS error: ${ex.message}")
    }

    override fun onStart() {
        onStatus("Waiting for client on port ${port}")
    }

    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        try { stop() } catch (_: Exception) {}
    }
}

// ── Foreground Service ─────────────────────────────────────────────

class TTSService : Service() {
    private var server: TTSServer? = null

    companion object {
        const val CHANNEL_ID = "orca_tts"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Orca TTS", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        server = TTSServer(this, 3457) { status ->
            try {
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(status))
            } catch (_: Exception) {}
        }
        server?.isReuseAddr = true
        server?.start()
        return START_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Orca TTS").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi).setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Orca TTS").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi).setOngoing(true).build()
        }
    }

    override fun onDestroy() { try { server?.shutdown() } catch (_: Exception) {}; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}

// ── Main Activity ──────────────────────────────────────────────────

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        toggleBtn = findViewById(R.id.toggleBtn)
        toggleBtn.setOnClickListener { if (running) stopSvc() else startSvc() }
        updateUI()
    }

    override fun onResume() { super.onResume(); updateUI() }

    private fun startSvc() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                return
            }
        }
        startForegroundService(Intent(this, TTSService::class.java))
        running = true; updateUI()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startSvc()
    }

    private fun stopSvc() { stopService(Intent(this, TTSService::class.java)); running = false; updateUI() }

    private fun updateUI() {
        toggleBtn.text = if (running) "Stop" else "Start"
        statusText.text = if (running) "Server running on port 3457" else "Ready"
    }
}
