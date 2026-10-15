package org.asr.runtime

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.launch
import org.asr.runtime.core.AsrService
import org.asr.runtime.core.RuntimeState
import org.asr.runtime.ros2.Ros2State
import org.asr.runtime.databinding.ActivityMainBinding
import org.asr.runtime.ui.MainViewModel
import org.asr.runtime.ui.TactileFeedbackHelper
import org.asr.runtime.ui.PersistentThemeController
import java.text.SimpleDateFormat
import java.util.*

/**
 * Enhanced Activity for ASR Runtime control and diagnostics.
 */
class  MainActivity : ComponentActivity() {

    private companion object {
        const val KEY_SENSOR_DISCLOSURE_ACCEPTED = "sensor_data_disclosure_v1_accepted"
    }

    private data class SensorConfigOption(val id: String, val label: String, val detail: String)

    private val sensorConfigOptions = listOf(
        SensorConfigOption("camera", "CAMERA", "Camera frames"),
        SensorConfigOption("imu", "IMU", "Accelerometer + gyroscope"),
        SensorConfigOption("environment", "ENVIRONMENT", "Light, pressure, temperature, humidity"),
        SensorConfigOption("motion", "MOTION", "Steps and motion detection"),
        SensorConfigOption("audio", "MICROPHONE", "Audio input"),
        SensorConfigOption("gps", "GPS", "Location data"),
        SensorConfigOption("battery", "BATTERY", "Level and charging state")
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var themeController: PersistentThemeController
    private var tcpConnected = false
    private var initializingControls = true
    private var pendingSensorSelection: Set<String>? = null
    private var sensorPanelExpanded = false
    private var initialSensorSelection = emptySet<String>()
    private val stagedSensorSelection = mutableSetOf<String>()
    private val sensorStatusViews = mutableMapOf<String, TextView>()
    private val sensorToggleViews = mutableMapOf<String, org.asr.runtime.ui.SquareSlideSwitch>()
    private var sensorAvailability = emptyMap<String, Boolean>()
    private var currentModuleStatus = emptyMap<String, String>()
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
            val asrService = binder.getService()
            binding.runtimeCard.txtRuntimeNode.text = asrService.getDeviceNodeName()
            viewModel.setService(asrService)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            viewModel.setService(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ensureConnectivityCardOrder()
        themeController = PersistentThemeController(this)
        themeController.apply(binding.root)
        updateThemeButton()
        binding.btnThemeToggle.setOnClickListener {
            themeController.cycle(binding.root)
            updateThemeButton()
            val dark = themeController.isDark
            updateTransportModeBadge()
            updateUsbTetheringSettingsButton()
            updateRuntimeStatus(viewModel.runtimeState.value)
            updateRos2StatusColor(viewModel.ros2Status.value.state)
            updateClientStatusIndicator(tcpConnected)
            if (binding.layoutAdvancedDetails.visibility == View.VISIBLE) {
                updatePermissionTable()
                updateHardwareTable()
            }
        }
        val ddsBackend = if (BuildConfig.ASR_DDS_BACKEND == "cyclone") {
            "CYCLONE DDS"
        } else {
            "FAST DDS"
        }
        binding.txtAppBackend.text = "$ddsBackend · V${BuildConfig.VERSION_NAME}"
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setupControls()
        setupSensorOutputPanel()
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

    private fun updateThemeButton() {
        val mode = themeController.mode
        binding.btnThemeToggle.contentDescription =
            "Theme mode is ${mode.accessibleName}. Tap to cycle System, Light, and Dark themes."
        binding.btnThemeToggle.setImageResource(mode.iconResource)
        binding.btnThemeToggle.imageTintList = ContextCompat.getColorStateList(
            this,
            if (themeController.isDark) android.R.color.white else android.R.color.black
        )
    }

    private fun ensureConnectivityCardOrder() {
        val container = binding.cardRos2Dds.parent as? LinearLayout ?: return
        val runtimeIndex = container.indexOfChild(binding.runtimeCard.root)
        if (runtimeIndex < 0) return
        container.removeView(binding.cardRos2Dds)
        container.removeView(binding.cardTcpTransport)
        container.addView(binding.cardRos2Dds, runtimeIndex + 1)
        container.addView(binding.cardTcpTransport, runtimeIndex + 2)
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
        val dark = themeController.isDark
        val primary = if (dark) Color.WHITE else Color.BLACK
        val secondary = if (dark) Color.parseColor("#A0A0A0") else Color.parseColor("#737373")
        val good = if (dark) Color.parseColor("#77C98A") else Color.parseColor("#39834B")
        val warning = if (dark) Color.parseColor("#E5B86B") else Color.parseColor("#9A6B17")
        val error = if (dark) Color.parseColor("#FF7373") else Color.parseColor("#B4232D")
        val horizontalPadding = (8 * resources.displayMetrics.density + 0.5f).toInt()
        val verticalPadding = (9 * resources.displayMetrics.density + 0.5f).toInt()
        val mono = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.space_mono)
        val row = TableRow(this)

        val labelText = TextView(this).apply {
            text = label.uppercase()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            typeface = mono
            textSize = 10f
            setTextColor(primary)
        }
        val presentText = TextView(this).apply {
            text = present
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            gravity = Gravity.CENTER
            typeface = mono
            letterSpacing = 0.02f
            setTextColor(if (isPresent) good else secondary)
            textSize = 10f
        }
        val stateText = TextView(this).apply {
            text = liveState
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            gravity = Gravity.CENTER
            typeface = mono
            letterSpacing = 0.01f
            setTextColor(when (liveState.uppercase()) {
                "RUNNING", "STREAMING", "ACTIVE" -> good
                "ERROR" -> error
                "STARTING", "STOPPING", "CONNECTING", "INITIALIZING" -> warning
                else -> secondary
            })
            textSize = 9f
        }

        row.addView(labelText)
        row.addView(presentText)
        row.addView(stateText)
        table.addView(row)
    }

    private fun addTableRow(table: TableLayout, label: String, value: String, isPositive: Boolean) {
        val dark = themeController.isDark
        val horizontalPadding = (8 * resources.displayMetrics.density + 0.5f).toInt()
        val verticalPadding = (9 * resources.displayMetrics.density + 0.5f).toInt()
        val mono = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.space_mono)
        val statusColor = when {
            isPositive && dark -> Color.parseColor("#77C98A")
            isPositive -> Color.parseColor("#39834B")
            dark -> Color.parseColor("#FF7373")
            else -> Color.parseColor("#B4232D")
        }
        val row = TableRow(this)
        val labelText = TextView(this).apply {
            text = label.replace('_', ' ')
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            typeface = mono
            textSize = 10f
            setTextColor(if (dark) Color.WHITE else Color.BLACK)
        }
        val valueText = TextView(this).apply {
            text = value
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            gravity = Gravity.CENTER
            typeface = mono
            letterSpacing = 0.02f
            setTextColor(statusColor)
            textSize = 10f
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

        val onDdsPathChanged = { button: android.widget.CompoundButton, checked: Boolean ->
            if (!initializingControls) {
                if (!checked || !requireSensorDataDisclosure(button)) {
                    TactileFeedbackHelper.playSliderSound(button, checked)
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
        }
        binding.switchDdsWifi.setOnCheckedChangeListener(onDdsPathChanged)
        binding.switchDdsUsb.setOnCheckedChangeListener(onDdsPathChanged)
        TactileFeedbackHelper.prepare(binding.switchDdsWifi)
        TactileFeedbackHelper.prepare(binding.switchDdsUsb)
        binding.btnUsbTetheringSettings.setOnClickListener { openUsbTetheringSettings() }

        binding.switchTcpEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (initializingControls) return@setOnCheckedChangeListener
            if (isChecked && requireSensorDataDisclosure(binding.switchTcpEnabled)) return@setOnCheckedChangeListener
            TactileFeedbackHelper.playSliderSound(binding.switchTcpEnabled, isChecked)
            getSharedPreferences("asr_prefs", MODE_PRIVATE).edit().putBoolean("tcp_enabled", isChecked).apply()
            updateTcpStatus()
            if (isChecked) startTransportServiceIntent(AsrService.ACTION_TCP_ENABLE, resolvedHost(), resolvedPort())
            else startTransportServiceIntent(AsrService.ACTION_TCP_DISABLE)
        }
        TactileFeedbackHelper.prepare(binding.switchTcpEnabled)

        binding.editTransportHost.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { updateTransportModeBadge() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        TactileFeedbackHelper.attachTactileFeedback(binding.runtimeCard.btnStartRuntime) {
            if (!hasAcceptedSensorDisclosure()) {
                if (!sensorPanelExpanded) toggleSensorOutputPanel()
                Toast.makeText(this, "Review and accept the data disclosure before starting the runtime", Toast.LENGTH_LONG).show()
                return@attachTactileFeedback
            }
            if (allPermissionsGranted()) {
                startServiceIntent(AsrService.ACTION_START)
            } else {
                requestPermissionsSafely()
            }
        }

        TactileFeedbackHelper.attachTactileFeedback(binding.runtimeCard.btnStopRuntime) {
            viewModel.stopRuntime()
        }

        TactileFeedbackHelper.attachTactileFeedback(binding.runtimeCard.btnRestartRuntime) {
            viewModel.restartRuntime()
            Toast.makeText(this, "Restarting ASR Runtime...", Toast.LENGTH_SHORT).show()
        }
        binding.runtimeCard.txtRuntimeStatus.setOnClickListener { toggleSensorOutputPanel() }

        // btnExitApp removed from header
        TactileFeedbackHelper.attachTactileFeedback(binding.btnDetailsToggle) {
            val showDetails = binding.layoutAdvancedDetails.visibility != android.view.View.VISIBLE
            binding.layoutAdvancedDetails.visibility = if (showDetails) android.view.View.VISIBLE else android.view.View.GONE
            binding.btnDetailsToggle.text = if (showDetails) {
                "⌃  HIDE DEVICE DETAILS & DIAGNOSTICS"
            } else {
                "⌄  SHOW DEVICE DETAILS & DIAGNOSTICS"
            }
            binding.btnDetailsToggle.contentDescription = if (showDetails) {
                "Hide device details and diagnostics"
            } else {
                "Show device details and diagnostics"
            }
            if (showDetails) {
                updatePermissionTable()
                updateHardwareTable()
            }
        }
        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, org.asr.runtime.ui.AboutActivity::class.java))
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

    private fun updateClientStatusIndicator(connected: Boolean) {
        val dark = themeController.isDark
        val color = when {
            connected && dark -> Color.parseColor("#77C98A")
            connected -> Color.parseColor("#39834B")
            dark -> Color.parseColor("#A0A0A0")
            else -> Color.parseColor("#737373")
        }
        binding.rowClientStatus.diagnosticIndicator.visibility = View.VISIBLE
        binding.rowClientStatus.diagnosticIndicator.setBackgroundColor(color)
        binding.rowClientStatus.valDiagnostic.text = if (connected) "CONNECTED" else "NOT CONNECTED"
        binding.rowClientStatus.valDiagnostic.contentDescription = if (connected) "TCP client connected" else "TCP client not connected"
        binding.rowClientStatus.valDiagnostic.setTextColor(color)
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
        binding.txtTransportMode.text = if (isAdb) "ADB" else "Wi-Fi"
        val dark = ::themeController.isInitialized && themeController.isDark
        val neutral = if (dark) Color.WHITE else Color.parseColor("#4A5568")
        binding.txtTransportMode.setTextColor(neutral)
        binding.txtTransportMode.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 2f * resources.displayMetrics.density
            setColor(Color.TRANSPARENT)
            setStroke((resources.displayMetrics.density + 0.5f).toInt(), if (dark) Color.GRAY else Color.parseColor("#A0A0A0"))
        }
        binding.txtTransportMode.contentDescription = if (isAdb) {
            "Connection mode indicator: ADB loopback"
        } else {
            "Connection mode indicator: Wi-Fi host"
        }
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
        val dark = ::themeController.isInitialized && themeController.isDark
        val warningAccent = usbPathSelected && tetheringMissing
        binding.txtUsbTetheringWarning.setTextColor(
            if (dark) Color.parseColor("#D4A843") else Color.parseColor("#8A641A")
        )
        val stroke = if (warningAccent) {
            if (dark) Color.parseColor("#D4A843") else Color.parseColor("#8A641A")
        } else if (dark) Color.LTGRAY else Color.DKGRAY
        val text = if (warningAccent) stroke else if (dark) Color.WHITE else Color.DKGRAY
        binding.btnUsbTetheringSettings.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 4f * resources.displayMetrics.density
            setColor(Color.TRANSPARENT)
            setStroke((resources.displayMetrics.density + 0.5f).toInt(), stroke)
        }
        binding.btnUsbTetheringSettings.backgroundTintList = null
        binding.btnUsbTetheringSettings.setTextColor(text)
    }

    private fun updateRos2StatusColor(state: Ros2State) {
        val dark = ::themeController.isInitialized && themeController.isDark
        binding.txtRos2Status.setTextColor(when (state) {
            Ros2State.ACTIVE -> if (dark) Color.parseColor("#77C98A") else Color.parseColor("#39834B")
            Ros2State.INITIALIZING, Ros2State.DDS_INITIALIZING,
            Ros2State.NODE_READY, Ros2State.WAITING_FOR_DISCOVERY,
            Ros2State.WAITING_FOR_INTERFACE -> if (dark) Color.parseColor("#E5B86B") else Color.parseColor("#9A6B17")
            Ros2State.ERROR -> if (dark) Color.parseColor("#FF7373") else Color.parseColor("#B4232D")
            else -> if (dark) Color.parseColor("#A0A0A0") else Color.parseColor("#737373")
        })
    }

    private fun configuredSensorModules(): Set<String> {
        val allowed = AsrService.CONFIGURABLE_SENSOR_MODULE_IDS.toSet()
        val saved = getSharedPreferences("asr_prefs", MODE_PRIVATE)
            .getStringSet("enabled_sensor_modules", null)
        return saved?.toSet()?.intersect(allowed) ?: allowed
    }

    private fun updateRuntimeStatus(state: RuntimeState) {
        val statusLabel = when (state) {
            RuntimeState.STOPPED -> "STOPPED"
            RuntimeState.STARTING -> "STARTING"
            RuntimeState.STREAMING -> "RUNNING"
            RuntimeState.STOPPING -> "STOPPING"
            RuntimeState.ERROR -> "ERROR"
        }
        val selectedCount = configuredSensorModules().size
        val totalCount = sensorConfigOptions.size
        val chevron = if (sensorPanelExpanded) "⌃" else "⌄"
        binding.runtimeCard.txtRuntimeStatus.text = "● $statusLabel · $selectedCount/$totalCount $chevron"
        binding.runtimeCard.txtRuntimeStatus.contentDescription =
            "Runtime $statusLabel. $selectedCount of $totalCount sensor groups selected. " +
                if (sensorPanelExpanded) "Tap to collapse sensor outputs." else "Tap to expand sensor outputs."
        val dark = ::themeController.isInitialized && themeController.isDark
        binding.runtimeCard.txtRuntimeStatus.setTextColor(when (state) {
            RuntimeState.STREAMING -> if (dark) Color.parseColor("#77C98A") else Color.parseColor("#39834B")
            RuntimeState.STARTING, RuntimeState.STOPPING -> if (dark) Color.parseColor("#E5B86B") else Color.parseColor("#9A6B17")
            RuntimeState.ERROR -> if (dark) Color.parseColor("#FF7373") else Color.parseColor("#B4232D")
            else -> if (dark) Color.parseColor("#A0A0A0") else Color.parseColor("#737373")
        })
    }

    private fun setupSensorOutputPanel() {
        val dark = themeController.isDark
        val primary = if (dark) Color.WHITE else Color.BLACK
        val secondary = if (dark) Color.parseColor("#A0A0A0") else Color.parseColor("#737373")
        val divider = if (dark) Color.parseColor("#333333") else Color.parseColor("#E3E5E8")
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        val mono = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.space_mono)
        val grotesk = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.space_grotesk)
        val rows = binding.runtimeCard.sensorOptionRows
        rows.removeAllViews()
        sensorStatusViews.clear()
        sensorToggleViews.clear()
        sensorAvailability = sensorConfigOptions.associate { it.id to isSensorGroupAvailable(it.id) }

        sensorConfigOptions.forEachIndexed { index, option ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(this).apply {
                text = option.label
                typeface = mono
                textSize = 12f
                letterSpacing = 0.04f
                setTextColor(primary)
            }
            val detail = TextView(this).apply {
                text = option.detail
                typeface = grotesk
                textSize = 12f
                setTextColor(secondary)
            }
            val status = TextView(this).apply {
                typeface = mono
                textSize = 10f
                letterSpacing = 0.03f
                setTextColor(secondary)
            }
            sensorStatusViews[option.id] = status
            textColumn.addView(name)
            textColumn.addView(detail)
            textColumn.addView(status)
            row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))

            val toggle = org.asr.runtime.ui.SquareSlideSwitch(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(76), dp(44)).apply { marginStart = dp(8) }
                setDarkTheme(dark)
                isChecked = option.id in configuredSensorModules()
                val available = sensorAvailability[option.id] == true
                isEnabled = available || isChecked
                alpha = if (available) 1f else 0.6f
                setOnCheckedChangeListener { _, checked ->
                    if (checked) stagedSensorSelection.add(option.id) else stagedSensorSelection.remove(option.id)
                    contentDescription = "${option.label}: ${if (checked) "selected" else "not selected"}"
                    alpha = if (available) 1f else 0.6f
                    updateSensorApplyButton()
                    updateSensorOptionStatuses(currentModuleStatus)
                }
            }
            toggle.contentDescription = "${option.label}: ${if (toggle.isChecked) "selected" else "not selected"}"
            sensorToggleViews[option.id] = toggle
            row.addView(toggle)
            rows.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (index < sensorConfigOptions.lastIndex) {
                rows.addView(View(this).apply { setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }

        binding.runtimeCard.btnCancelSensorSelection.setOnClickListener { closeSensorOutputPanel(discardChanges = true) }
        binding.runtimeCard.checkSensorDataDisclosure.setOnCheckedChangeListener { _, _ -> updateSensorApplyButton() }
        binding.runtimeCard.btnApplySensorSelection.setOnClickListener {
            applySensorSelection(stagedSensorSelection.toSet())
        }
        updateSensorApplyButton()
        updateSensorOptionStatuses(currentModuleStatus)
    }

    private fun toggleSensorOutputPanel() {
        if (sensorPanelExpanded) {
            closeSensorOutputPanel(discardChanges = true)
        } else {
            initialSensorSelection = configuredSensorModules()
            stagedSensorSelection.clear()
            stagedSensorSelection.addAll(initialSensorSelection)
            binding.runtimeCard.checkSensorDataDisclosure.isChecked = hasAcceptedSensorDisclosure()
            sensorToggleViews.forEach { (id, toggle) ->
                toggle.isChecked = id in stagedSensorSelection
                toggle.contentDescription = "${sensorConfigOptions.first { it.id == id }.label}: ${if (toggle.isChecked) "selected" else "not selected"}"
            }
            sensorPanelExpanded = true
            TransitionManager.beginDelayedTransition(binding.runtimeCard.root as ViewGroup, AutoTransition().apply { duration = 180 })
            binding.runtimeCard.layoutSensorOutputs.visibility = View.VISIBLE
            updateRuntimeStatus(viewModel.runtimeState.value)
            updateSensorApplyButton()
            updateSensorOptionStatuses(currentModuleStatus)
        }
    }

    private fun closeSensorOutputPanel(discardChanges: Boolean) {
        if (!sensorPanelExpanded) return
        if (discardChanges) {
            stagedSensorSelection.clear()
            stagedSensorSelection.addAll(initialSensorSelection)
            sensorToggleViews.forEach { (id, toggle) ->
                toggle.isChecked = id in stagedSensorSelection
                toggle.contentDescription = "${sensorConfigOptions.first { it.id == id }.label}: ${if (toggle.isChecked) "selected" else "not selected"}"
            }
        }
        sensorPanelExpanded = false
        TransitionManager.beginDelayedTransition(binding.runtimeCard.root as ViewGroup, AutoTransition().apply { duration = 180 })
        binding.runtimeCard.layoutSensorOutputs.visibility = View.GONE
        updateRuntimeStatus(viewModel.runtimeState.value)
    }

    private fun updateSensorApplyButton() {
        val button = binding.runtimeCard.btnApplySensorSelection
        val changed = stagedSensorSelection != initialSensorSelection
        val consentGiven = binding.runtimeCard.checkSensorDataDisclosure.isChecked
        val consentRequired = !hasAcceptedSensorDisclosure()
        button.text = if (consentRequired && consentGiven) {
            "AGREE & CONTINUE"
        } else if (stagedSensorSelection.isNotEmpty()) {
            "AGREE & APPLY ${stagedSensorSelection.size}"
        } else {
            "APPLY 0 SELECTED"
        }
        button.isEnabled = (changed || (consentRequired && consentGiven)) &&
            (stagedSensorSelection.isEmpty() || consentGiven)
        button.alpha = if (button.isEnabled) 1f else 0.45f
        button.contentDescription = if (consentRequired && consentGiven) {
            "Accept the data disclosure and save sensor output settings"
        } else {
            "Apply ${stagedSensorSelection.size} selected sensor groups"
        }
    }

    private fun updateSensorOptionStatuses(moduleStatus: Map<String, String>) {
        currentModuleStatus = moduleStatus
        val dark = themeController.isDark
        sensorConfigOptions.forEach { option ->
            val state = moduleStatus[option.id]?.uppercase() ?: "STOPPED"
            val available = sensorAvailability[option.id] == true
            val label = when {
                !available -> "NOT PRESENT"
                state == "RUNNING" -> "RUNNING"
                state == "ERROR" -> "ERROR"
                state == "STARTING" -> "STARTING"
                state == "STOPPING" -> "STOPPING"
                option.id in stagedSensorSelection -> "SELECTED"
                else -> "OFF"
            }
            sensorStatusViews[option.id]?.apply {
                text = label
                setTextColor(if (label == "RUNNING") {
                    if (dark) Color.parseColor("#77C98A") else Color.parseColor("#39834B")
                } else if (label == "ERROR") {
                    if (dark) Color.parseColor("#FF7373") else Color.parseColor("#B4232D")
                } else if (dark) Color.parseColor("#A0A0A0") else Color.parseColor("#737373"))
            }
        }
    }

    private fun isSensorGroupAvailable(moduleId: String): Boolean {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return when (moduleId) {
            "camera" -> packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            "audio" -> packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            "gps" -> packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
            "imu" -> sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null ||
                sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
            "environment" -> listOf(Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE, Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY)
                .any { sensorManager.getDefaultSensor(it) != null }
            "motion" -> listOf(Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_SIGNIFICANT_MOTION)
                .any { sensorManager.getDefaultSensor(it) != null }
            "battery" -> true
            else -> false
        }
    }

    private fun applySensorSelection(selection: Set<String>) {
        if (selection.isNotEmpty() && !binding.runtimeCard.checkSensorDataDisclosure.isChecked) {
            Toast.makeText(this, "Review and accept the sensor data disclosure to enable outputs", Toast.LENGTH_LONG).show()
            return
        }
        if (binding.runtimeCard.checkSensorDataDisclosure.isChecked) {
            getSharedPreferences("asr_prefs", MODE_PRIVATE).edit()
                .putBoolean(KEY_SENSOR_DISCLOSURE_ACCEPTED, true)
                .apply()
        }
        val missingPermissions = permissionsForSensors(selection).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            pendingSensorSelection = selection
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
            return
        }
        commitSensorSelection(selection)
        closeSensorOutputPanel(discardChanges = false)
    }

    private fun commitSensorSelection(selection: Set<String>) {
        val selected = selection.intersect(AsrService.CONFIGURABLE_SENSOR_MODULE_IDS.toSet())
        getSharedPreferences("asr_prefs", MODE_PRIVATE).edit()
            .putStringSet("enabled_sensor_modules", selected)
            .apply()
        viewModel.setEnabledSensorModuleIds(selected)
        initialSensorSelection = selected
        stagedSensorSelection.clear()
        stagedSensorSelection.addAll(selected)
        updateRuntimeStatus(viewModel.runtimeState.value)
        updateSensorApplyButton()
        updateSensorOptionStatuses(currentModuleStatus)
    }

    private fun hasAcceptedSensorDisclosure(): Boolean = getSharedPreferences("asr_prefs", MODE_PRIVATE)
        .getBoolean(KEY_SENSOR_DISCLOSURE_ACCEPTED, false)

    private fun requireSensorDataDisclosure(button: android.widget.CompoundButton): Boolean {
        if (hasAcceptedSensorDisclosure()) return false
        initializingControls = true
        button.isChecked = false
        initializingControls = false
        if (!sensorPanelExpanded) toggleSensorOutputPanel()
        Toast.makeText(this, "Review and accept the data disclosure before enabling a connection", Toast.LENGTH_LONG).show()
        return true
    }

    private fun permissionsForSensors(moduleIds: Set<String>): List<String> = buildList {
        if ("camera" in moduleIds) add(Manifest.permission.CAMERA)
        if ("audio" in moduleIds) add(Manifest.permission.RECORD_AUDIO)
        if ("gps" in moduleIds) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if ("motion" in moduleIds && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (moduleIds.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
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
                        updateRuntimeStatus(state)
                    }
                }

                launch {
                    viewModel.moduleStatus.collect { status ->
                        currentModuleStatus = status
                        if (sensorPanelExpanded) updateSensorOptionStatuses(status)
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
                            binding.runtimeCard.txtRuntimeUptime.text = formatUptime(diag.uptimeMs)
                            binding.runtimeCard.btnStartRuntime.isEnabled = false
                        } else {
                            binding.runtimeCard.txtRuntimeUptime.text = "00:00:00"
                            binding.runtimeCard.btnStartRuntime.isEnabled = true
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
                            // Keep the host-derived mode indicator accurate while the service is unavailable.
                            updateTransportModeBadge()
                        }
                    }
                }

                launch {
                    viewModel.clientInfo.collect { info ->
                        tcpConnected = info != null
                        updateTcpStatus()
                        val isConnected = (info != null)
                        if (isConnected) {
                            updateClientStatusIndicator(connected = true)
                            binding.rowClientApp.valDiagnostic.text = info!!.application
                            binding.rowClientHost.valDiagnostic.text = "${info.host}:${info.port}"
                            binding.rowClientSince.valDiagnostic.text = formatTime(info.connectedSince)
                            
                            binding.btnConnectTransport.text = "CONNECTED"
                            binding.btnConnectTransport.isEnabled = false
                            binding.btnDisconnectTransport.isEnabled = true
                            binding.btnReconnectTransport.isEnabled = true
                        } else {
                            updateClientStatusIndicator(connected = false)
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
                        val waitingForDiscovery = when(status.state) {
                            Ros2State.INITIALIZING, Ros2State.DDS_INITIALIZING,
                            Ros2State.NODE_READY, Ros2State.WAITING_FOR_DISCOVERY,
                            Ros2State.WAITING_FOR_INTERFACE -> true
                            else -> false
                        }
                        updateRos2StatusColor(status.state)
                        binding.progressRos2.visibility = if (waitingForDiscovery) {
                            android.view.View.VISIBLE
                        } else {
                            android.view.View.GONE
                        }
                        if (waitingForDiscovery) {
                            binding.progressRos2.startIndeterminate()
                        } else {
                            binding.progressRos2.stopIndeterminate()
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

    private fun allPermissionsGranted() = permissionsForSensors(configuredSensorModules()).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionsSafely() {
        ActivityCompat.requestPermissions(
            this,
            permissionsForSensors(configuredSensorModules()).toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                val selection = pendingSensorSelection
                pendingSensorSelection = null
                if (selection != null) {
                    commitSensorSelection(selection)
                    closeSensorOutputPanel(discardChanges = false)
                } else startServiceIntent(AsrService.ACTION_START)
            } else {
                pendingSensorSelection = null
                Toast.makeText(this, "Permissions required for ASR", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unbindService(connection)
    }
}
