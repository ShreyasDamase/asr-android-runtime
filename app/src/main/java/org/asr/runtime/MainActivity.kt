package org.asr.runtime

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.asr.runtime.core.AsrService
import org.asr.runtime.core.RuntimeState
import org.asr.runtime.ros2.Ros2State
import org.asr.runtime.databinding.ActivityMainBinding
import org.asr.runtime.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enhanced Activity for ASR Runtime control and diagnostics.
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private val PERMISSION_REQUEST_CODE = 1001
    private val requiredPermissions: Array<String> by lazy {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.toTypedArray()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AsrService.LocalBinder
            viewModel.setService(binder.getService())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.setService(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupControls()
        binding.switchRos2Enabled.isChecked = getSharedPreferences("asr_prefs", MODE_PRIVATE).getBoolean("ros2_enabled", false)
        updateDeviceIdentity()
        observeState()
        updatePermissionTable()
        updateHardwareTable()

        // Bind to the service if it's already running to get live status
        val intent = Intent(this, AsrService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        updateDeviceIdentity()
        updatePermissionTable()
        updateHardwareTable()
    }

    private fun updateDeviceIdentity() {
        val prefs = getSharedPreferences("asr_prefs", MODE_PRIVATE)
        val uuidStr = prefs.getString("device_id", null)
            ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("device_id", it).apply()
            }
        val deviceId = (uuidStr.hashCode() and 0xFF).toByte()
        val alias = prefs.getString("node_alias", null) ?: "asr_android"
        val ros2 = viewModel.ros2Status.value
        val domainId = ros2.domainId

        binding.txtDeviceId.text = "Device ID: asr_android_$deviceId"
        binding.txtRos2NodeName.text = ros2.nodeName ?: "${alias}_${deviceId}"
        binding.txtRos2Namespace.text = ros2.nodeNamespace ?: "/asr_android_${deviceId}"
        binding.txtRos2DomainId.text = domainId.toString()

        binding.txtRos2Status.text = "● ${ros2.state.name}"
        binding.txtRos2Status.setTextColor(when (ros2.state) {
            Ros2State.ACTIVE -> Color.parseColor("#2E7D32")
            Ros2State.INITIALIZING -> Color.parseColor("#3182CE")
            Ros2State.ERROR -> Color.parseColor("#E53E3E")
            Ros2State.DISCONNECTED -> Color.parseColor("#F9A825")
            Ros2State.DISABLED -> Color.parseColor("#9AA0A6")
        })
    }

    private fun updatePermissionTable() {
        val table = binding.permissionTable
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
        val table = binding.hardwareTable
        // Keep header row
        val headerCount = 1
        while (table.childCount > headerCount) {
            table.removeViewAt(headerCount)
        }

        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val isLocationEnabled = locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false
        val hasGpsPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val moduleStatus = viewModel.moduleStatus.value
        val ros2Enabled = getSharedPreferences("asr_prefs", MODE_PRIVATE).getBoolean("ros2_enabled", false)
        val isRuntimeRunning = viewModel.runtimeState.value in listOf(RuntimeState.CONNECTED, RuntimeState.STREAMING, RuntimeState.STARTING, RuntimeState.CONNECTING)

        val hardwareMap = listOf(
            Triple("Camera", packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY), "camera"),
            Triple("Microphone", packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE), "audio"),
            Triple("Accelerometer", (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null), "imu"),
            Triple("Gyroscope", (sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null), "imu"),
            Triple("Environment", (sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null), "environment"),
            Triple("GPS", packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS), "gps"),
            Triple("Speaker", true, "speaker"),
            Triple("Battery", true, "battery")
        )

        hardwareMap.forEach { (name, isPresent, moduleId) ->
            var liveState = moduleStatus[moduleId] ?: "STOPPED"

            // Distinguish BLOCKED states
            if (name == "GPS" && isPresent) {
                if (!isLocationEnabled || !hasGpsPermission) {
                    if (isRuntimeRunning) {
                        liveState = "BLOCKED"
                    }
                }
            } else if (name == "Camera" && isPresent) {
                val hasCamPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (!hasCamPerm && isRuntimeRunning) {
                    liveState = "BLOCKED"
                }
            } else if (name == "Microphone" && isPresent) {
                val hasMicPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (!hasMicPerm && isRuntimeRunning) {
                    liveState = "BLOCKED"
                }
            }

            val rosState = when {
                !isPresent -> "N/A"
                !ros2Enabled -> "NOT PUBLISHING"
                liveState == "RUNNING" || liveState == "STREAMING" -> "PUBLISHING"
                else -> "NOT PUBLISHING"
            }

            addHardwareRow(
                table,
                name,
                if (isPresent) "AVAILABLE" else "NOT AVAILABLE",
                isPresent,
                liveState,
                rosState
            )
        }
    }

    private fun addHardwareRow(
        table: TableLayout,
        label: String,
        hardwareAvailability: String,
        isPresent: Boolean,
        liveState: String,
        rosPublishState: String
    ) {
        val row = TableRow(this).apply {
            setPadding(0, 4, 0, 4)
        }
        
        val labelText = TextView(this).apply {
            text = label
            setPadding(6, 6, 6, 6)
            textSize = 12f
            setTextColor(Color.parseColor("#2D3748"))
        }
        
        val hwText = TextView(this).apply {
            text = hardwareAvailability
            setPadding(6, 6, 6, 6)
            gravity = Gravity.CENTER
            setTextColor(if (isPresent) Color.parseColor("#2E7D32") else Color.parseColor("#A0AEC0"))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 11f
        }

        val stateText = TextView(this).apply {
            text = liveState
            setPadding(6, 6, 6, 6)
            gravity = Gravity.CENTER
            val color = when(liveState) {
                "RUNNING", "STREAMING" -> Color.parseColor("#2E7D32")
                "ERROR" -> Color.parseColor("#E53E3E")
                "BLOCKED" -> Color.parseColor("#DD6B20")
                "STARTING", "STOPPING", "CONNECTING" -> Color.parseColor("#3182CE")
                else -> Color.parseColor("#718096")
            }
            setTextColor(color)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 11f
        }

        val rosText = TextView(this).apply {
            text = rosPublishState
            setPadding(6, 6, 6, 6)
            gravity = Gravity.CENTER
            val color = when(rosPublishState) {
                "PUBLISHING" -> Color.parseColor("#1565C0")
                "NOT PUBLISHING" -> Color.parseColor("#718096")
                else -> Color.parseColor("#A0AEC0")
            }
            setTextColor(color)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 11f
        }

        row.addView(labelText)
        row.addView(hwText)
        row.addView(stateText)
        row.addView(rosText)
        table.addView(row)
    }

    private fun addTableRow(table: TableLayout, label: String, value: String, isPositive: Boolean) {
        val row = TableRow(this).apply {
            setPadding(0, 4, 0, 4)
        }
        
        val labelText = TextView(this).apply {
            text = label
            setPadding(8, 8, 8, 8)
            textSize = 14f
        }
        
        val valueText = TextView(this).apply {
            text = value
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER
            setTextColor(if (isPositive) Color.parseColor("#2E7D32") else Color.RED)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 14f
        }

        row.addView(labelText)
        row.addView(valueText)
        table.addView(row)
    }

    private fun setupControls() {
        // Populate defaults
        binding.editTransportHost.setText("")
        binding.editTransportPort.setText("4242")
        updateTransportModeBadge()

        binding.switchRos2Enabled.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("asr_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("ros2_enabled", isChecked)
                .apply()
            updateDeviceIdentity()
            updateHardwareTable()
            if (viewModel.isServiceBound.value) {
                viewModel.setRos2Enabled(isChecked)
                Toast.makeText(this, if (isChecked) "ROS 2 enabled" else "ROS 2 disabled", Toast.LENGTH_SHORT).show()
            }
        }

        binding.editTransportHost.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateTransportModeBadge() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnStartRuntime.setOnClickListener {
            if (allPermissionsGranted()) {
                startServiceIntent(AsrService.ACTION_START)
            } else {
                requestPermissionsSafely()
            }
        }
        binding.btnStopRuntime.setOnClickListener { 
            viewModel.stopRuntime()
        }
        binding.btnRestartRuntime.setOnClickListener { 
            viewModel.restartRuntime()
            Toast.makeText(this, "Restarting ASR Runtime...", Toast.LENGTH_SHORT).show()
        }

        binding.btnConnectTransport.setOnClickListener {
            if (!viewModel.isServiceBound.value) {
                Toast.makeText(this, "Runtime not started — tap START first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val host = resolvedHost()
            val port = resolvedPort()
            viewModel.reconnectTransport(host, port)
        }
        binding.btnDisconnectTransport.setOnClickListener {
            if (!viewModel.isServiceBound.value) {
                Toast.makeText(this, "Runtime not started", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.stopTransport()
        }
        binding.btnReconnectTransport.setOnClickListener {
            if (!viewModel.isServiceBound.value) {
                Toast.makeText(this, "Runtime not started — tap START first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val host = resolvedHost()
            val port = resolvedPort()
            viewModel.reconnectTransport(host, port)
        }

        binding.btnEmergencyReset.setOnClickListener {
            viewModel.resetRuntime()
            Toast.makeText(this, "Relaunching App...", Toast.LENGTH_SHORT).show()

            // Trigger fresh application restart
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
    }

    /** Returns the host entered by the user, or "127.0.0.1" as the ADB default. */
    private fun resolvedHost(): String {
        val text = binding.editTransportHost.text?.toString()?.trim() ?: ""
        return if (text.isBlank()) "127.0.0.1" else text
    }

    /** Returns the port entered by the user, or 4242 as default. */
    private fun resolvedPort(): Int {
        val text = binding.editTransportPort.text?.toString()?.trim() ?: ""
        return text.toIntOrNull()?.takeIf { it in 1..65535 } ?: 4242
    }

    /** Updates the Wi-Fi / ADB badge and ADB hint visibility. */
    private fun updateTransportModeBadge() {
        val host = binding.editTransportHost.text?.toString()?.trim() ?: ""
        val isAdb = host.isBlank() || host == "127.0.0.1" || host == "::1"
        binding.txtTransportMode.text  = if (isAdb) "ADB" else "Wi-Fi"
        binding.txtTransportMode.setTextColor(if (isAdb) android.graphics.Color.parseColor("#805AD5") else android.graphics.Color.parseColor("#3182CE"))
        binding.txtTransportMode.setBackgroundColor(if (isAdb) android.graphics.Color.parseColor("#FAF5FF") else android.graphics.Color.parseColor("#EBF8FF"))
        binding.txtAdbHint.visibility = if (isAdb) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun startServiceIntent(actionStr: String) {
        val intent = Intent(this, AsrService::class.java).apply { action = actionStr }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.runtimeState.collect { state ->
                        binding.txtRuntimeStatus.text = "● ${state.name}"
                        binding.txtRuntimeStatus.setTextColor(when(state) {
                            RuntimeState.STREAMING, RuntimeState.CONNECTED -> Color.parseColor("#2E7D32")
                            RuntimeState.STARTING, RuntimeState.STOPPING, RuntimeState.CONNECTING -> Color.parseColor("#3182CE")
                            RuntimeState.ERROR -> Color.parseColor("#E53E3E")
                            else -> Color.parseColor("#9AA0A6")
                        })
                        updateDeviceIdentity()
                        updateHardwareTable()
                    }
                }

                launch {
                    viewModel.ros2Status.collect {
                        updateDeviceIdentity()
                        updateHardwareTable()
                    }
                }

                launch {
                    viewModel.moduleStatus.collect {
                        updateHardwareTable()
                    }
                }

                launch {
                    viewModel.diagnostics.collect { diag ->
                        binding.txtUptimeValue.text = formatUptime(diag.uptimeMs)
                        binding.rowPacketsSent.valDiagnostic.text = diag.packetsSent.toString()
                        binding.rowPacketsReceived.valDiagnostic.text = diag.packetsReceived.toString()
                        binding.rowQueueDepth.valDiagnostic.text = diag.queueDepth.toString()
                        binding.rowDropped.valDiagnostic.text = diag.droppedPackets.toString()
                        binding.rowReconnects.valDiagnostic.text = diag.reconnectCount.toString()
                        
                        val state = viewModel.runtimeState.value
                        if (state == RuntimeState.CONNECTED || state == RuntimeState.STREAMING || state == RuntimeState.CONNECTING || state == RuntimeState.STARTING) {
                            binding.btnStartRuntime.text = "${formatUptime(diag.uptimeMs)}"
                            binding.btnStartRuntime.isEnabled = false
                        } else {
                            binding.btnStartRuntime.text = "START"
                            binding.btnStartRuntime.isEnabled = true
                        }
                    }
                }

                launch {
                    viewModel.isServiceBound.collect { bound ->
                        // Enable transport controls only when the service is bound.
                        // Presses before binding are otherwise silent no-ops.
                        val enable = bound
                        binding.btnConnectTransport.isEnabled    = enable
                        binding.btnDisconnectTransport.isEnabled = enable
                        binding.btnReconnectTransport.isEnabled  = enable
                        binding.editTransportHost.isEnabled      = enable
                        binding.editTransportPort.isEnabled      = enable
                        if (!enable) {
                            // If service died, reset transport badge to default
                            binding.txtTransportMode.text = "ADB"
                        }
                    }
                }

                launch {
                    viewModel.clientInfo.collect { info ->
                        val isConnected = (info != null)
                        if (isConnected) {
                            binding.rowClientStatus.valDiagnostic.text = "🟢 Connected"
                            binding.rowClientApp.valDiagnostic.text = info!!.application
                            binding.rowClientHost.valDiagnostic.text = "${info.host}:${info.port}"
                            binding.rowClientSince.valDiagnostic.text = formatTime(info.connectedSince)
                            
                            binding.btnConnectTransport.text = "CONNECTED"
                            binding.btnConnectTransport.isEnabled = false
                            binding.btnDisconnectTransport.isEnabled = true
                            binding.btnReconnectTransport.isEnabled = true
                        } else {
                            binding.rowClientStatus.valDiagnostic.text = "⚪ Not Connected"
                            binding.rowClientApp.valDiagnostic.text = "-"
                            binding.rowClientHost.valDiagnostic.text = "-"
                            binding.rowClientSince.valDiagnostic.text = "-"
                            
                            binding.btnConnectTransport.text = "CONNECT"
                            binding.btnConnectTransport.isEnabled = viewModel.isServiceBound.value
                            binding.btnDisconnectTransport.isEnabled = false
                            binding.btnReconnectTransport.isEnabled = false
                        }
                    }
                }
            }
        }
        
        // Setup static labels
        binding.rowPacketsSent.lblDiagnostic.text = "Packets Sent"
        binding.rowPacketsReceived.lblDiagnostic.text = "Packets Received"
        binding.rowQueueDepth.lblDiagnostic.text = "Queue Depth"
        binding.rowDropped.lblDiagnostic.text = "Dropped Packets"
        binding.rowReconnects.lblDiagnostic.text = "Reconnect Count"
        
        binding.rowClientStatus.lblDiagnostic.text = "Status"
        binding.rowClientApp.lblDiagnostic.text = "Application"
        binding.rowClientHost.lblDiagnostic.text = "Host/Port"
        binding.rowClientSince.lblDiagnostic.text = "Connected Since"
    }

    private fun formatUptime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60))
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun formatTime(ms: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsSafely() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                viewModel.startRuntime()
                startServiceIntent(AsrService.ACTION_START)
            } else {
                Toast.makeText(this, "Permissions required for ASR", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(connection)
    }
}
