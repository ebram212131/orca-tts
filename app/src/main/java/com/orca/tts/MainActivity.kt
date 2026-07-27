package com.orca.tts

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.orca.tts.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var server: TTSServer? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleBtn.setOnClickListener {
            if (running) stopServer() else startServer()
        }
    }

    private fun startServer() {
        try {
            server = TTSServer(this, 3457) { status ->
                runOnUiThread { binding.statusText.text = status }
            }
            server?.isReuseAddr = true
            server?.start()
            running = true
            binding.toggleBtn.text = "Stop"
            binding.statusText.text = "Server started on port 3457"
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopServer() {
        try {
            server?.shutdown()
            server = null
            running = false
            binding.toggleBtn.text = "Start"
            binding.statusText.text = "Server stopped"
        } catch (e: Exception) {
            Toast.makeText(this, "Error stopping: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (running) stopServer()
    }
}
