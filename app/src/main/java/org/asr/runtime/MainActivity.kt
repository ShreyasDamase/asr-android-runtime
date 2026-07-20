package org.asr.runtime

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.asr.runtime.core.AsrService

/**
 * Minimal Activity to request permissions and bootstrap the ASR Runtime.
 */
class MainActivity : Activity() {

    private val PERMISSION_REQUEST_CODE = 1001
    private var asrService: AsrService? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateHardwareTable()
            handler.postDelayed(this, 1000)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AsrService.LocalBinder
            asrService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            asrService = null
        }
    }

    private val requiredPermissions: Array<String> by lazy {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updatePermissionTable()
        updateHardwareTable()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (allPermissionsGranted()) {
                startRuntime()
                Toast.makeText(this, "Runtime Started", Toast.LENGTH_SHORT).show()
            } else {
                requestPermissionsSafely()
            }
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            openAppSettings()
        }

        // Bind to the service if it's already running to get live status
        val intent = Intent(this, AsrService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun requestPermissionsSafely() {
        val shouldShowRationale = requiredPermissions.any { 
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }

        if (shouldShowRationale) {
            // The user has denied at least once. Show a dialog or just request again.
            // In ASR context, we simply request again or guide to settings.
            ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
        } else {
            // First time or "Don't ask again" is checked.
            // If we know some are denied and rationale is false, they likely checked "Don't ask again"
            if (requiredPermissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Please enable permissions in Settings", Toast.LENGTH_LONG).show()
                openAppSettings()
            } else {
                ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        // Refresh tables when returning from settings
        updatePermissionTable()
        updateHardwareTable()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(connection)
    }

    private fun updatePermissionTable() {
        val table = findViewById<TableLayout>(R.id.permissionTable)
        // Keep header row
        val headerCount = 1
        while (table.childCount > headerCount) {
            table.removeViewAt(headerCount)
        }

        requiredPermissions.forEach { permission ->
            val status = ContextCompat.checkSelfPermission(this, permission)
            val isGranted = status == PackageManager.PERMISSION_GRANTED
            addTableRow(table, permission.substringAfterLast("."), if (isGranted) "GRANTED" else "DENIED", isGranted)
        }
    }

    private fun updateHardwareTable() {
        val table = findViewById<TableLayout>(R.id.hardwareTable)
        // Keep header row
        val headerCount = 1
        while (table.childCount > headerCount) {
            table.removeViewAt(headerCount)
        }

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val moduleStatus: Map<String, String> = asrService?.getModuleStatus() ?: emptyMap()
        
        val hardwareMap = listOf(
            Triple("Camera (Back)", packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY), "camera"),
            Triple("Microphone", packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE), "audio"),
            Triple("Accelerometer", (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null), "imu"),
            Triple("Gyroscope", (sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null), "imu"),
            Triple("Light Sensor", (sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null), "environment"),
            Triple("Proximity", (sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null), "environment"),
            Triple("GPS", packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS), "gps"),
            Triple("Speaker", true, "speaker"),
            Triple("Battery", true, "battery")
        )

        hardwareMap.forEach { (name, isPresent, moduleId) ->
            val liveState: String = moduleStatus[moduleId] ?: "STOPPED"
            addHardwareRow(table, name, if (isPresent) "YES" else "NO", isPresent, liveState)
        }
    }

    private fun addHardwareRow(table: TableLayout, label: String, present: String, isPresent: Boolean, liveState: String) {
        val row = TableRow(this).apply {
            setPadding(0, 4, 0, 4)
        }
        
        val labelText = TextView(this).apply {
            text = label
            setPadding(8, 8, 8, 8)
        }
        
        val presentText = TextView(this).apply {
            text = present
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER
            setTextColor(if (isPresent) Color.parseColor("#2E7D32") else Color.RED)
            textStyle = android.graphics.Typeface.BOLD
        }

        val stateText = TextView(this).apply {
            text = liveState
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER
            val color = when(liveState) {
                "RUNNING" -> Color.parseColor("#2E7D32")
                "ERROR" -> Color.RED
                "STARTING", "STOPPING" -> Color.BLUE
                else -> Color.GRAY
            }
            setTextColor(color)
            textStyle = android.graphics.Typeface.BOLD
        }

        row.addView(labelText)
        row.addView(presentText)
        row.addView(stateText)
        table.addView(row)
    }

    private fun addTableRow(table: TableLayout, label: String, value: String, isPositive: Boolean) {
        val row = TableRow(this).apply {
            setPadding(0, 4, 0, 4)
        }
        
        val labelText = TextView(this).apply {
            text = label
            setPadding(8, 8, 8, 8)
        }
        
        val valueText = TextView(this).apply {
            text = value
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER
            setTextColor(if (isPositive) Color.parseColor("#2E7D32") else Color.RED)
            textStyle = android.graphics.Typeface.BOLD
        }

        row.addView(labelText)
        row.addView(valueText)
        table.addView(row)
    }

    var TextView.textStyle: Int
        get() = typeface.style
        set(value) {
            setTypeface(typeface, value)
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updatePermissionTable()
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startRuntime()
                Toast.makeText(this, "Permissions Granted. Runtime Started.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some permissions were denied.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startRuntime() {
        val intent = Intent(this, AsrService::class.java).apply {
            action = AsrService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}
