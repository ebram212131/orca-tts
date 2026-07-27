package com.orca.tts

import android.content.Context
import android.speech.tts.TextToSpeech
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

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
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
                            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "tts_${System.currentTimeMillis()}")
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
