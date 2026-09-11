package org.asr.runtime

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.asr.runtime.core.AsrService
import org.asr.runtime.databinding.ActivityMainBinding
import org.asr.runtime.ui.*

/**
 * Enhanced Activity for ASR Runtime control and diagnostics.
 * Now implementing the updated reference UI design.
 */
class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private lateinit var sensorAdapter: SensorAdapter

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

        setupRecyclerView()
        setupListeners()
        observeState()

        // Bind to the service
        val intent = Intent(this, AsrService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun setupRecyclerView() {
        sensorAdapter = SensorAdapter(
            onToggle = { viewModel.onSensorToggle(it) },
            onStart = { viewModel.onSensorStart(it) },
            onStop = { viewModel.onSensorStop(it) }
        )
        binding.rvSensors.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sensorAdapter
        }
    }

    private fun setupListeners() {
        val runtimeCard = binding.cardRuntime
        runtimeCard.btnStart.setOnClickListener {
            if (allPermissionsGranted()) {
                viewModel.onStartRuntime()
            } else {
                requestPermissionsSafely()
            }
        }
        runtimeCard.btnStop.setOnClickListener { viewModel.onStopRuntime() }
        runtimeCard.btnRestart.setOnClickListener { viewModel.onRestartRuntime() }

        val tcpCard = binding.cardTcp
        tcpCard.btnConnect.setOnClickListener {
            val host = tcpCard.etHost.text?.toString() ?: "127.0.0.1"
            val port = tcpCard.etPort.text?.toString()?.toIntOrNull() ?: 4242
            viewModel.onTcpConnect(host, port)
        }
        tcpCard.btnDisconnect.setOnClickListener { viewModel.onTcpDisconnect() }
        tcpCard.btnReconnect.setOnClickListener {
            val host = tcpCard.etHost.text?.toString() ?: "127.0.0.1"
            val port = tcpCard.etPort.text?.toString()?.toIntOrNull() ?: 4242
            viewModel.onTcpConnect(host, port)
        }
        tcpCard.switchAdb.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                tcpCard.etHost.setText("127.0.0.1")
                tcpCard.tvAdbHint.visibility = View.VISIBLE
            } else {
                tcpCard.tvAdbHint.visibility = View.GONE
            }
        }

        binding.cardRos2.switchRos.setOnCheckedChangeListener { _, isChecked ->
            viewModel.onRos2EnabledChanged(isChecked)
        }

        binding.btnResetRuntime.setOnClickListener {
            viewModel.onResetRuntime()
            Toast.makeText(this, "Emergency Reset Triggered", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.runtimeUiState.collectLatest { state ->
                        binding.cardRuntime.statusRuntime.setStatus(state.runtimeState.label, state.runtimeState.color)
                        binding.cardRuntime.tvUptimeValue.text = state.uptime
                        binding.cardRuntime.tvDeviceValue.text = state.deviceId
                        
                        binding.cardRuntime.btnStart.isEnabled = state.runtimeState == RuntimeState.STOPPED || state.runtimeState == RuntimeState.ERROR
                        binding.cardRuntime.btnStop.isEnabled = state.runtimeState == RuntimeState.RUNNING
                        binding.cardRuntime.btnRestart.isEnabled = state.runtimeState == RuntimeState.RUNNING
                    }
                }

                launch {
                    viewModel.tcpUiState.collectLatest { state ->
                        binding.cardTcp.statusTcp.setStatus(state.state.label, state.state.color)
                        binding.cardTcp.tvClientValue.text = state.connectedClient
                        if (binding.cardTcp.etHost.text.isNullOrBlank()) {
                            binding.cardTcp.etHost.setText(state.host)
                        }
                        if (binding.cardTcp.etPort.text.isNullOrBlank()) {
                            binding.cardTcp.etPort.setText(state.port)
                        }
                    }
                }

                launch {
                    viewModel.ros2UiState.collectLatest { state ->
                        binding.cardRos2.switchRos.isChecked = state.enabled
                        binding.cardRos2.statusRos.setStatus(state.discovery.label, state.discovery.color)
                        binding.cardRos2.tvDomainValue.text = state.domainId
                        binding.cardRos2.tvNodeValue.text = state.nodeName
                        binding.cardRos2.tvNamespaceValue.text = state.nodeNamespace
                    }
                }

                launch {
                    viewModel.sensors.collectLatest { list ->
                        sensorAdapter.submitList(list)
                    }
                }

                launch {
                    viewModel.diagnostics.collectLatest { diag ->
                        binding.cardDiagnostics.rowPacketsSent.tvLabel.text = "Packets Sent"
                        binding.cardDiagnostics.rowPacketsSent.tvValue.text = diag.packetsSent.toString()
                        
                        binding.cardDiagnostics.rowPacketsReceived.tvLabel.text = "Packets Received"
                        binding.cardDiagnostics.rowPacketsReceived.tvValue.text = diag.packetsReceived.toString()
                        
                        binding.cardDiagnostics.rowQueueDepth.tvLabel.text = "Queue Depth"
                        binding.cardDiagnostics.rowQueueDepth.tvValue.text = diag.queueDepth.toString()
                        
                        binding.cardDiagnostics.rowDropped.tvLabel.text = "Dropped Packets"
                        binding.cardDiagnostics.rowDropped.tvValue.text = diag.droppedPackets.toString()
                        
                        binding.cardDiagnostics.rowReconnects.tvLabel.text = "Reconnect Count"
                        binding.cardDiagnostics.rowReconnects.tvValue.text = diag.reconnectCount.toString()
                    }
                }
            }
        }
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
                viewModel.onStartRuntime()
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
