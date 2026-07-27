package com.orca.tts

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private var server: TTSServer? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleBtn = findViewById(R.id.toggleBtn)

        toggleBtn.setOnClickListener {
            if (running) stopServer() else startServer()
        }
    }

    private fun startServer() {
        try {
            server = TTSServer(this, 3457) { status ->
                runOnUiThread { statusText.text = status }
            }
            server?.isReuseAddr = true
            server?.start()
            running = true
            toggleBtn.text = "Stop"
            statusText.text = "Server started on port 3457"
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopServer() {
        try {
            server?.shutdown()
            server = null
            running = false
            toggleBtn.text = "Start"
            statusText.text = "Server stopped"
        } catch (e: Exception) {
            Toast.makeText(this, "Error stopping: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) stopServer()
    }
}
