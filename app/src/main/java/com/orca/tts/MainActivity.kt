package com.orca.tts

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleBtn = findViewById(R.id.toggleBtn)

        toggleBtn.setOnClickListener {
            if (running) stopService() else startService()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun startService() {
        val intent = Intent(this, TTSService::class.java)
        startForegroundService(intent)
        running = true
        updateUI()
    }

    private fun stopService() {
        val intent = Intent(this, TTSService::class.java)
        stopService(intent)
        running = false
        updateUI()
    }

    private fun updateUI() {
        if (running) {
            toggleBtn.text = "Stop"
            statusText.text = "Server running on port 3457"
        } else {
            toggleBtn.text = "Start"
            statusText.text = "Ready"
        }
    }
}
