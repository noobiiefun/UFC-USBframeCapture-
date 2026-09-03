package com.ufc.app.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Switch
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
        val spinnerResolution = findViewById<Spinner>(R.id.spinnerResolution)
        val spinnerFormat = findViewById<Spinner>(R.id.spinnerFormat)
        val editFps = findViewById<EditText>(R.id.editFps)
        val editBitrate = findViewById<EditText>(R.id.editBitrate)
        val switchAudioMonitor = findViewById<Switch>(R.id.switchAudioMonitor)
        val switchOpengl = findViewById<Switch>(R.id.switchOpengl)
        val radioLandscape = findViewById<RadioButton>(R.id.radioLandscape)
        val radioPortrait = findViewById<RadioButton>(R.id.radioPortrait)
        val btnSave = findViewById<Button>(R.id.btnSave)

        // Setup Spinners
        val resOptions = arrayOf("1920x1080", "1280x720", "854x480", "640x360")
        spinnerResolution.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resOptions)
        
        val formatOptions = arrayOf("MJPEG (Fast)", "YUYV (Slow)")
        spinnerFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, formatOptions)

        // Load existing
        editRtmpUrl.setText(config.rtmpUrl)
        editStreamKey.setText(config.streamKey)
        
        val currentRes = "${config.resolutionWidth}x${config.resolutionHeight}"
        val resIndex = resOptions.indexOf(currentRes).coerceAtLeast(0)
        spinnerResolution.setSelection(resIndex)
        
        spinnerFormat.setSelection(if (config.useMjpeg) 0 else 1)
        
        editFps.setText(config.fps.toString())
        editBitrate.setText(config.bitrateKbps.toString())
        switchAudioMonitor.isChecked = config.monitorAudio
        switchOpengl.isChecked = config.useOpengl
        
        if (config.isPortrait) {
            radioPortrait.isChecked = true
        } else {
            radioLandscape.isChecked = true
        }

        btnSave.setOnClickListener {
            config.rtmpUrl = editRtmpUrl.text.toString()
            config.streamKey = editStreamKey.text.toString()
            
            val selectedRes = resOptions[spinnerResolution.selectedItemPosition].split("x")
            config.resolutionWidth = selectedRes[0].toInt()
            config.resolutionHeight = selectedRes[1].toInt()
            
            config.useMjpeg = spinnerFormat.selectedItemPosition == 0
            config.fps = editFps.text.toString().toIntOrNull() ?: 30
            config.bitrateKbps = editBitrate.text.toString().toIntOrNull() ?: 2500
            config.isPortrait = radioPortrait.isChecked
            config.monitorAudio = switchAudioMonitor.isChecked
            config.useOpengl = switchOpengl.isChecked

            Toast.makeText(this, "Settings Saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
