package org.asr.runtime.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.asr.runtime.MainActivity
import org.asr.runtime.databinding.ActivityPermissionOnboardingBinding
import org.asr.runtime.databinding.ItemPermissionOnboardingRowBinding

/**
 * First-launch permission onboarding activity designed in Nothing UI aesthetic.
 */
class PermissionOnboardingActivity : ComponentActivity() {

    private lateinit var binding: ActivityPermissionOnboardingBinding

    data class PermissionItem(
        val permission: String,
        val title: String,
        val description: String,
        val minSdk: Int = 0
    )

    private val permissionSpecs = listOf(
        PermissionItem(
            Manifest.permission.CAMERA,
            "CAMERA",
            "Streams video frames to camera/image/compressed topic."
        ),
        PermissionItem(
            Manifest.permission.RECORD_AUDIO,
            "MICROPHONE",
            "Publishes raw audio samples to audio/raw topic."
        ),
        PermissionItem(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "PRECISE GPS",
            "Publishes geographic coordinates to gps (NavSatFix) topic."
        ),
        PermissionItem(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION else "",
            "MOTION SENSORS",
            "Access high-rate IMU & step tracking without OS throttling.",
            minSdk = Build.VERSION_CODES.Q
        ),
        PermissionItem(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else "",
            "NOTIFICATIONS",
            "Maintains persistent foreground service for ROS 2 background streaming.",
            minSdk = Build.VERSION_CODES.TIRAMISU
        )
    )

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            renderRows()
            if (allGranted()) {
                markCompletedAndNavigate()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if already completed or if all already granted
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

        if (isCompleted || allGranted()) {
            navigateToMain()
            return
        }

        binding = ActivityPermissionOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderRows()

        binding.btnGrantPermissions.setOnClickListener {
            val needed = getApplicablePermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            if (needed.isNotEmpty()) {
                requestMultiplePermissions.launch(needed)
            } else {
                markCompletedAndNavigate()
            }
        }

        binding.btnSkipOnboarding.setOnClickListener {
            markCompletedAndNavigate()
        }
    }

    override fun onResume() {
        super.onResume()
        renderRows()
    }

    private fun getApplicablePermissions(): List<String> {
        return permissionSpecs
            .filter { Build.VERSION.SDK_INT >= it.minSdk && it.permission.isNotEmpty() }
            .map { it.permission }
    }

    private fun allGranted(): Boolean {
        return getApplicablePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun renderRows() {
        binding.layoutPermissionRows.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (item in permissionSpecs) {
            if (Build.VERSION.SDK_INT < item.minSdk || item.permission.isEmpty()) continue

            val rowBinding = ItemPermissionOnboardingRowBinding.inflate(inflater, binding.layoutPermissionRows, false)
            rowBinding.txtPermTitle.text = item.title
            rowBinding.txtPermDesc.text = item.description

            val isGranted = ContextCompat.checkSelfPermission(this, item.permission) == PackageManager.PERMISSION_GRANTED
            if (isGranted) {
                rowBinding.txtPermStatus.text = "[ GRANTED ]"
                rowBinding.txtPermStatus.setTextColor(Color.parseColor("#4A9E5C")) // Nothing success green
            } else {
                rowBinding.txtPermStatus.text = "[ REQUIRED ]"
                rowBinding.txtPermStatus.setTextColor(Color.parseColor("#999999")) // Nothing secondary text
            }

            binding.layoutPermissionRows.addView(rowBinding.root)
        }

        if (allGranted()) {
            binding.btnGrantPermissions.text = "CONTINUE TO RUNTIME"
        } else {
            binding.btnGrantPermissions.text = "GRANT PERMISSIONS"
        }
    }

    private fun markCompletedAndNavigate() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, true)
            .apply()
        navigateToMain()
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    companion object {
        const val PREFS_NAME = "asr_prefs"
        const val KEY_ONBOARDING_COMPLETED = "permission_onboarding_completed"
    }
}
