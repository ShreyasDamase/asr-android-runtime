package org.asr.runtime

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
class  MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private var tcpConnected = false
    private var initializingControls = true
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (binding.layoutAdvancedDetails.visibility == android.view.View.VISIBLE) {
                updatePermissionTable()
                updateHardwareTable()
            }
            handler.postDelayed(this, 1000)
        }
    }

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
        val prefs = getSharedPreferences("asr_prefs", MODE_PRIVATE)
        val legacyRos2Enabled = prefs.getBoolean("ros2_enabled", false)
        val wifiDdsEnabled = prefs.getBoolean("dds_wifi_enabled", legacyRos2Enabled)
        val usbDdsEnabled = prefs.getBoolean("dds_usb_enabled", false)
        binding.switchDdsWifi.isChecked = wifiDdsEnabled
        binding.switchDdsUsb.isChecked = usbDdsEnabled
        binding.txtDdsPathStatus.text = buildDdsPathSummary(wifiDdsEnabled, usbDdsEnabled, "")
        binding.switchTcpEnabled.isChecked = prefs.getBoolean("tcp_enabled", false)
        initializingControls = false
        observeState()
        updatePermissionTable()
        updateHardwareTable()

        // Bind to the service if it's already running to get live status
        val intent = Intent(this, AsrService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionTable()
        updateHardwareTable()
        updateUsbTetheringSettingsButton()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
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
        val moduleStatus = viewModel.moduleStatus.value
        
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
            val liveState = moduleStatus[moduleId] ?: "STOPPED"
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
            textSize = 14f
        }
        
        val presentText = TextView(this).apply {
            text = present
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER
            setTextColor(if (isPresent) Color.parseColor("#2E7D32") else Color.RED)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 14f
        }

        val stateText = TextView(this).apply {
            text = liveState
            setPadding(8, 8, 8, 8)
            gravity = Gravity.CENTER
            val color = when(liveState) {
                "RUNNING", "STREAMING" -> Color.parseColor("#2E7D32")
                "ERROR" -> Color.RED
                "STARTING", "STOPPING", "CONNECTING" -> Color.BLUE
                else -> Color.GRAY
            }
            setTextColor(color)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 14f
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

        val onDdsPathChanged = { _: android.widget.CompoundButton, _: Boolean ->
            if (!initializingControls) {
                val wifi = binding.switchDdsWifi.isChecked
                val usb = binding.switchDdsUsb.isChecked
                getSharedPreferences("asr_prefs", MODE_PRIVATE).edit()
                    .putBoolean("dds_wifi_enabled", wifi)
                    .putBoolean("dds_usb_enabled", usb)
                    .putBoolean("ros2_enabled", wifi || usb)
                    .apply()
                binding.txtDdsPathStatus.text = buildDdsPathSummary(wifi, usb, "")
                updateUsbTetheringSettingsButton()
                startDdsPathIntent(wifi, usb)
            }
        }
        binding.switchDdsWifi.setOnCheckedChangeListener(onDdsPathChanged)
        binding.switchDdsUsb.setOnCheckedChangeListener(onDdsPathChanged)
        binding.btnUsbTetheringSettings.setOnClickListener { openUsbTetheringSettings() }

        binding.switchTcpEnabled.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("asr_prefs", MODE_PRIVATE).edit().putBoolean("tcp_enabled", isChecked).apply()
            updateTcpStatus()
            if (isChecked) startTransportServiceIntent(AsrService.ACTION_TCP_ENABLE, resolvedHost(), resolvedPort())
            else startTransportServiceIntent(AsrService.ACTION_TCP_DISABLE)
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

        binding.btnExitApp.setOnClickListener { confirmExit() }
        binding.btnDetailsToggle.setOnClickListener {
            val showDetails = binding.layoutAdvancedDetails.visibility != android.view.View.VISIBLE
            binding.layoutAdvancedDetails.visibility = if (showDetails) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnDetailsToggle.text = if (showDetails) "Hide device details and diagnostics" else "Show device details and diagnostics"
            if (showDetails) {
                updatePermissionTable()
                updateHardwareTable()
            }
        }

        binding.btnConnectTransport.setOnClickListener {
            if (binding.switchTcpEnabled.isChecked) {
                startTransportServiceIntent(AsrService.ACTION_TCP_ENABLE, resolvedHost(), resolvedPort())
            } else {
                binding.switchTcpEnabled.isChecked = true
            }
        }
        binding.btnDisconnectTransport.setOnClickListener {
            binding.switchTcpEnabled.isChecked = false
        }
        binding.btnReconnectTransport.setOnClickListener {
            if (binding.switchTcpEnabled.isChecked) {
                startTransportServiceIntent(AsrService.ACTION_TCP_ENABLE, resolvedHost(), resolvedPort())
            } else {
                binding.switchTcpEnabled.isChecked = true
            }
        }

    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Exit ASR Android?")
            .setMessage("This stops the sensor runtime and shuts down TCP and ROS 2 before closing the app.")
            .setNegativeButton("No", null)
            .setPositiveButton("Yes, exit") { _, _ ->
                binding.btnExitApp.isEnabled = false
                if (viewModel.isServiceBound.value) {
                    viewModel.shutdownForExit {
                        if (!isFinishing) finishAndRemoveTask()
                    }
                } else {
                    stopService(Intent(this, AsrService::class.java))
                    finishAndRemoveTask()
                }
            }
            .show()
    }

    private fun updateTcpStatus() {
        val status = when {
            tcpConnected -> "CONNECTED"
            binding.switchTcpEnabled.isChecked -> "CONNECTING"
            else -> "OFF"
        }
        binding.txtTcpStatus.text = status
        binding.txtTcpStatus.setTextColor(when (status) {
            "CONNECTED" -> Color.parseColor("#2E7D32")
            "CONNECTING" -> Color.parseColor("#3182CE")
            else -> Color.parseColor("#718096")
        })
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

    private fun startTransportServiceIntent(action: String, host: String? = null, port: Int? = null) {
        val intent = Intent(this, AsrService::class.java).apply {
            this.action = action
            host?.let { putExtra("host", it) }
            port?.let { putExtra("port", it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action != AsrService.ACTION_TCP_DISABLE && action != AsrService.ACTION_ROS2_DISABLE) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startDdsPathIntent(wifiEnabled: Boolean, usbEnabled: Boolean) {
        val intent = Intent(this, AsrService::class.java).apply {
            action = AsrService.ACTION_DDS_PATHS_SET
            putExtra("wifi_enabled", wifiEnabled)
            putExtra("usb_enabled", usbEnabled)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (wifiEnabled || usbEnabled)) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun buildDdsPathSummary(wifi: Boolean, usb: Boolean, detail: String): String {
        if (!wifi && !usb) return "DDS network paths are disabled"
        if (detail.isNotBlank()) return detail
        val selected = buildList {
            if (wifi) add("Wi-Fi")
            if (usb) add("USB")
        }.joinToString(" + ")
        return "Selected: $selected"
    }

    private fun updateUsbTetheringSettingsButton() {
        val usbInterfaceAvailable = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { networkInterface ->
                val name = networkInterface.name.lowercase()
                networkInterface.isUp && !networkInterface.isLoopback &&
                    listOf("rndis", "usb", "ncm", "ecm", "eth", "enx").any { prefix ->
                        name.startsWith(prefix)
                    }
            }
        }.getOrDefault(false)

        val usbPathSelected = binding.switchDdsUsb.isChecked
        val tetheringMissing = !usbInterfaceAvailable
        binding.txtUsbTetheringWarning.visibility = if (tetheringMissing) {
            binding.txtUsbTetheringWarning.text = if (usbPathSelected) {
                "USB DDS is selected, but USB tethering is off. Enable it to connect."
            } else {
                "USB tethering is off. Enable it here before using USB DDS."
            }
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        binding.btnUsbTetheringSettings.visibility = if (tetheringMissing) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        val buttonTint = if (usbPathSelected && tetheringMissing) "#F6AD55" else "#E2E8F0"
        val buttonText = if (usbPathSelected && tetheringMissing) "#7B341E" else "#2D3748"
        binding.btnUsbTetheringSettings.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(buttonTint))
        binding.btnUsbTetheringSettings.setTextColor(Color.parseColor(buttonText))
    }

    private fun openUsbTetheringSettings() {
        val settingsIntents = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
            Intent(android.provider.Settings.ACTION_SETTINGS)
        )
        for (settingsIntent in settingsIntents) {
            try {
                startActivity(settingsIntent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Try the next system settings page supported by this Android build.
            }
        }
        Toast.makeText(this, "USB tethering settings are unavailable on this device", Toast.LENGTH_LONG).show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.runtimeState.collect { state ->
                        binding.txtRuntimeStatus.text = "● ${when (state) {
                            RuntimeState.STOPPED -> "SENSORS STOPPED"
                            RuntimeState.STARTING -> "SENSORS STARTING"
                            RuntimeState.STREAMING -> "SENSORS RUNNING"
                            RuntimeState.STOPPING -> "SENSORS STOPPING"
                            RuntimeState.ERROR -> "SENSOR ERROR"
                        }}"
                        binding.txtRuntimeStatus.setTextColor(when(state) {
                            RuntimeState.STREAMING -> Color.GREEN
                            RuntimeState.STARTING, RuntimeState.STOPPING -> Color.BLUE
                            RuntimeState.ERROR -> Color.RED
                            else -> Color.GRAY
                        })
                    }
                }

                launch {
                    viewModel.diagnostics.collect { diag ->
                        binding.rowPacketsSent.valDiagnostic.text = diag.packetsSent.toString()
                        binding.rowPacketsReceived.valDiagnostic.text = diag.packetsReceived.toString()
                        binding.rowQueueDepth.valDiagnostic.text = diag.queueDepth.toString()
                        binding.rowDropped.valDiagnostic.text = diag.droppedPackets.toString()
                        binding.rowReconnects.valDiagnostic.text = diag.reconnectCount.toString()
                        
                        val state = viewModel.runtimeState.value
                        if (state == RuntimeState.STREAMING || state == RuntimeState.STARTING) {
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
                        tcpConnected = info != null
                        updateTcpStatus()
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

                launch {
                    viewModel.ros2Status.collect { status ->
                        binding.txtRos2Status.text = when (status.state) {
                            Ros2State.INITIALIZING -> "INITIALIZING"
                            Ros2State.DDS_INITIALIZING -> "DDS INIT"
                            Ros2State.NODE_READY -> "NODE READY"
                            Ros2State.WAITING_FOR_DISCOVERY -> "WAITING DISCOVERY"
                            Ros2State.WAITING_FOR_INTERFACE -> "WAITING FOR LINK"
                            Ros2State.ACTIVE -> "ACTIVE (DISCOVERED)"
                            Ros2State.ERROR -> "ERROR"
                            Ros2State.DISCONNECTED -> "DISCONNECTED"
                            Ros2State.DISABLED -> "DISABLED"
                        }
                        binding.txtRos2Status.setTextColor(when(status.state) {
                            Ros2State.ACTIVE -> Color.parseColor("#2E7D32")
                            Ros2State.INITIALIZING, Ros2State.DDS_INITIALIZING,
                            Ros2State.NODE_READY, Ros2State.WAITING_FOR_DISCOVERY,
                            Ros2State.WAITING_FOR_INTERFACE -> Color.BLUE
                            Ros2State.ERROR -> Color.RED
                            else -> Color.GRAY
                        })
                        binding.progressRos2.visibility = when(status.state) {
                            Ros2State.INITIALIZING, Ros2State.DDS_INITIALIZING,
                            Ros2State.NODE_READY, Ros2State.WAITING_FOR_DISCOVERY,
                            Ros2State.WAITING_FOR_INTERFACE -> android.view.View.VISIBLE
                            else -> android.view.View.GONE
                        }
                        val routeDescription = status.pathSummary
                        binding.txtDdsPathStatus.text = buildDdsPathSummary(
                            binding.switchDdsWifi.isChecked,
                            binding.switchDdsUsb.isChecked,
                            routeDescription
                        )
                        updateUsbTetheringSettingsButton()
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
