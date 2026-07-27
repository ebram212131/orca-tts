package com.orca.tts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.msgpack.core.MessageUnpacker
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class TTSServer(
    context: Context,
    port: Int = 3457,
    private val onStatus: (String) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    private var tts: android.speech.tts.TextToSpeech? = null

    init {
        tts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                onStatus("TTS Ready - port $port")
            }
        }
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        onStatus("Client connected")
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        onStatus("Server running - port ${port}")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {}

    override fun onMessage(conn: WebSocket?, message: ByteBuffer?) {
        message ?: return
        try {
            val data = ByteArray(message.remaining())
            message.get(data)
            val unpacker = org.msgpack.core.MessagePack.newDefaultUnpacker(data)
            val arraySize = unpacker.unpackArrayHeader()
            if (arraySize >= 1) {
                val cmd = unpacker.unpackString()
                when (cmd) {
                    "SpeakText" -> {
                        if (arraySize >= 2) {
                            val text = unpacker.unpackString()
                            tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "tts")
                        }
                    }
                    "CancelSpeech" -> tts?.stop()
                }
            }
            unpacker.close()
        } catch (e: Exception) {
            Log.e("TTSServer", "Parse error", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("TTSServer", "WS error", ex)
    }

    override fun onStart() {
        onStatus("Server running - port ${port}")
    }

    fun shutdown() {
        try { tts?.stop() } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
        try { stop() } catch (_: Exception) {}
    }
}

class TTSService : Service() {

    private var server: TTSServer? = null

    companion object {
        const val CHANNEL_ID = "orca_tts"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
        } catch (e: Exception) {
            Log.e("TTSService", "Channel error", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = buildNotification("Starting...")
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e("TTSService", "startForeground error", e)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            server = TTSServer(this, 3457) { status ->
                try {
                    val n = buildNotification(status)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, n)
                } catch (_: Exception) {}
            }
            server?.isReuseAddr = true
            server?.start()
        } catch (e: Exception) {
            Log.e("TTSService", "Server start error", e)
            stopSelf()
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Orca TTS",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Orca TTS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Orca TTS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        try { server?.shutdown() } catch (_: Exception) {}
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
