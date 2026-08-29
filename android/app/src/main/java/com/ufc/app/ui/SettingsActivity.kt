package com.ufc.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ufc.app.R
import com.ufc.app.model.StreamConfig

class SettingsActivity : AppCompatActivity() {

    private lateinit var config: StreamConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        config = StreamConfig(this)

        val editRtmpUrl = findViewById<EditText>(R.id.editRtmpUrl)
        val editStreamKey = findViewById<EditText>(R.id.editStreamKey)
        val editWidth = findViewById<EditText>(R.id.editWidth)
        val editHeight = findViewById<EditText>(R.id.editHeight)
        val editFps = findViewById<EditText>(R.id.editFps)
        val editBitrate = findViewById<EditText>(R.id.editBitrate)
        val radioLandscape = findViewById<RadioButton>(R.id.radioLandscape)
        val radioPortrait = findViewById<RadioButton>(R.id.radioPortrait)
        val btnSave = findViewById<Button>(R.id.btnSave)

        // Load existing
        editRtmpUrl.setText(config.rtmpUrl)
        editStreamKey.setText(config.streamKey)
        editWidth.setText(config.resolutionWidth.toString())
        editHeight.setText(config.resolutionHeight.toString())
        editFps.setText(config.fps.toString())
        editBitrate.setText(config.bitrateKbps.toString())
        if (config.isPortrait) {
            radioPortrait.isChecked = true
        } else {
            radioLandscape.isChecked = true
        }

        btnSave.setOnClickListener {
            config.rtmpUrl = editRtmpUrl.text.toString()
            config.streamKey = editStreamKey.text.toString()
            config.resolutionWidth = editWidth.text.toString().toIntOrNull() ?: 1280
            config.resolutionHeight = editHeight.text.toString().toIntOrNull() ?: 720
            config.fps = editFps.text.toString().toIntOrNull() ?: 30
            config.bitrateKbps = editBitrate.text.toString().toIntOrNull() ?: 2500
            config.isPortrait = radioPortrait.isChecked

            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
