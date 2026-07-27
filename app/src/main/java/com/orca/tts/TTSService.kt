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
    private var ready = false

    init {
        tts = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                ready = true
                onStatus("TTS Ready")
            }
        }
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        onStatus("Client connected")
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        onStatus("Client disconnected")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        // text messages ignored
    }

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
                            tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "tts_${System.currentTimeMillis()}")
                        }
                    }
                    "CancelSpeech" -> {
                        tts?.stop()
                    }
                }
            }
            unpacker.close()
        } catch (e: Exception) {
            Log.e("TTSServer", "Error parsing message", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("TTSServer", "Error", ex)
    }

    override fun onStart() {
        onStatus("Server running on port ${port}")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        stop()
    }
}

class TTSService : Service() {

    private var server: TTSServer? = null
    private var notificationManager: NotificationManager? = null

    companion object {
        const val CHANNEL_ID = "orca_tts_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.orca.tts.STOP"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("Server running on port 3457")
        startForeground(NOTIFICATION_ID, notification)

        startServer()

        return START_STICKY
    }

    private fun startServer() {
        try {
            server = TTSServer(this, 3457) { status ->
                updateNotification(status)
            }
            server?.isReuseAddr = true
            server?.start()
        } catch (e: Exception) {
            Log.e("TTSService", "Error starting server", e)
            stopSelf()
        }
    }

    private fun stopServer() {
        server?.shutdown()
        server = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Orca TTS Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Orca TTS WebSocket Server"
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, TTSService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Orca TTS Server")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openPendingIntent)
                .addAction(
                    Notification.Action.Builder(
                        null, "Stop", stopPendingIntent
                    ).build()
                )
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Orca TTS Server")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(openPendingIntent)
                .addAction(
                    Notification.Action.Builder(
                        null, "Stop", stopPendingIntent
                    ).build()
                )
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
