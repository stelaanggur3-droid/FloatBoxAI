package com.floatbox.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.floatbox.ai.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUiState()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        startFloatBoxService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootScroll) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isSmallBubble = prefs.getBoolean(KEY_BUBBLE_SMALL, true)
        if (isSmallBubble) {
            binding.rbBubbleSmall.isChecked = true
        } else {
            binding.rbBubbleNormal.isChecked = true
        }

        binding.rgBubbleSize.setOnCheckedChangeListener { _, checkedId ->
            val smallSelected = checkedId == R.id.rbBubbleSmall
            prefs.edit().putBoolean(KEY_BUBBLE_SMALL, smallSelected).apply()
            if (FloatingOverlayService.isRunning) {
                val updateIntent = Intent(this, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_UPDATE_BUBBLE_SIZE
                }
                startService(updateIntent)
            }
        }

        binding.btnGrantOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        binding.btnToggleFloatBox.setOnClickListener {
            if (FloatingOverlayService.isRunning) {
                stopService(Intent(this, FloatingOverlayService::class.java))
                binding.root.postDelayed({ updateUiState() }, 150)
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(
                        this,
                        "Aktifkan izin Display over other apps terlebih dahulu.",
                        Toast.LENGTH_SHORT
                    ).show()
                    requestOverlayPermission()
                    return@setOnClickListener
                }
                checkNotificationAndStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlaySettingsLauncher.launch(intent)
    }

    private fun checkNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startFloatBoxService()
    }

    private fun startFloatBoxService() {
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = FloatingOverlayService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
        binding.root.postDelayed({ updateUiState() }, 200)
    }

    private fun updateUiState() {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            binding.tvPermissionStatus.text = getString(R.string.permission_overlay_granted)
            binding.tvPermissionStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_ok)
            )
            binding.btnGrantOverlay.isEnabled = false
            binding.btnGrantOverlay.alpha = 0.6f
        } else {
            binding.tvPermissionStatus.text = getString(R.string.permission_overlay_denied)
            binding.tvPermissionStatus.setTextColor(
                ContextCompat.getColor(this, R.color.status_warn)
            )
            binding.btnGrantOverlay.isEnabled = true
            binding.btnGrantOverlay.alpha = 1.0f
        }

        if (FloatingOverlayService.isRunning) {
            binding.btnToggleFloatBox.text = getString(R.string.btn_stop_floatbox)
            binding.btnToggleFloatBox.setBackgroundColor(
                ContextCompat.getColor(this, R.color.status_warn)
            )
        } else {
            binding.btnToggleFloatBox.text = getString(R.string.btn_start_floatbox)
            binding.btnToggleFloatBox.setBackgroundColor(
                ContextCompat.getColor(this, R.color.navy_primary)
            )
        }
    }

    companion object {
        const val PREFS_NAME = "floatbox_prefs"
        const val KEY_BUBBLE_SMALL = "bubble_small"
    }
}