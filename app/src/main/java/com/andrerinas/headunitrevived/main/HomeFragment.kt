package com.andrerinas.headunitrevived.main

import com.andrerinas.headunitrevived.ui.GlassView
import com.andrerinas.headunitrevived.ui.GlassMotion.applySpringPress
import com.andrerinas.headunitrevived.ui.GlassMotion.springFadeIn
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import androidx.core.content.getSystemService
import com.andrerinas.headunitrevived.connection.CommManager.ConnectionState
import com.andrerinas.headunitrevived.ui.GlassView.GlassState
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.graphics.Color
import android.content.res.ColorStateList
import android.widget.Toast
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import android.net.ConnectivityManager
import android.os.Build
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.NearbyManager
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import android.content.res.Configuration
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import com.andrerinas.headunitrevived.utils.AppLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.utils.VpnControl

class HomeFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            AppLog.i("VPN permission granted. Starting DummyVpnService and Self Mode.")
            VpnControl.startVpn(requireContext());
            startSelfModeInternal()
        } else {
            AppLog.w("VPN permission denied. Offline Self Mode might fail.")
            Toast.makeText(requireContext(), getString(R.string.failed_start_android_auto), Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var selfModePanel: GlassView
    private lateinit var usbPanel: GlassView
    private lateinit var wifiPanel: GlassView
    private lateinit var settingsPanel: GlassView
    private lateinit var settingsGear: View
    private lateinit var statusBar: GlassView
    private lateinit var audioOutputText: TextView
    private lateinit var micStatusText: TextView
    private lateinit var exitButton: Button
    
    private lateinit var selfModeStatus: TextView
    private lateinit var usbStatus: TextView
    private lateinit var wifiStatus: TextView

    private var hasAttemptedAutoConnect = false
    private var hasAttemptedSingleUsbAutoConnect = false

    private fun updateWifiButtonFeedback(scanning: Boolean) {
        if (scanning) {
            wifiStatus.text = getString(R.string.searching)
            wifiPanel.setGlassState(GlassState.ACTIVE)
        } else {
            wifiStatus.text = getString(R.string.cd_status_not_connected)
            wifiPanel.setGlassState(GlassState.IDLE)
        }
    }

    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                updateAudioStatus()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                updateAudioStatus()
            }
        }
    } else null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selfModePanel = view.findViewById(R.id.self_mode_panel)
        usbPanel = view.findViewById(R.id.usb_panel)
        wifiPanel = view.findViewById(R.id.wifi_panel)
        settingsPanel = view.findViewById(R.id.settings_panel)
        settingsGear = view.findViewById(R.id.settings_gear)
        statusBar = view.findViewById(R.id.status_bar)
        audioOutputText = view.findViewById(R.id.audio_output_text)
        micStatusText = view.findViewById(R.id.mic_status_text)
        exitButton = view.findViewById(R.id.exit_button)
        
        selfModeStatus = view.findViewById(R.id.self_mode_status)
        usbStatus = view.findViewById(R.id.usb_status)
        wifiStatus = view.findViewById(R.id.wifi_status)

        setupListeners()
        
        // Initial state update
        updatePanelStates(commManager.connectionState.value)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { state ->
                    updatePanelStates(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AapService.scanningState.collect { updateWifiButtonFeedback(it) }
            }
        }

        // Apply motion
        listOf(selfModePanel, usbPanel, wifiPanel, settingsPanel, statusBar).forEach { it.applySpringPress() }
        
        // Staggered entry
        val panels = listOf(selfModePanel, usbPanel, wifiPanel, settingsPanel)
        panels.forEachIndexed { index, panel ->
            if (panel.visibility != View.GONE) panel.springFadeIn(index * 60L)
        }
        statusBar.springFadeIn(panels.size * 60L)

        val appSettings = App.provide(requireContext()).settings
        
        // Toggle gear vs setup based on wizard completion
        if (!appSettings.hasCompletedSetupWizard) {
            settingsPanel.visibility = View.VISIBLE
            settingsGear.visibility = View.GONE
        } else {
            settingsPanel.visibility = View.GONE
            settingsGear.visibility = View.VISIBLE
        }

        if (appSettings.autoStartOnScreenOn || appSettings.autoStartOnBoot) {
            ContextCompat.startForegroundService(requireContext(),
                Intent(requireContext(), AapService::class.java))
        }

        for (methodId in appSettings.autoConnectPriorityOrder) {
            if (commManager.isConnected) break
            when (methodId) {
                Settings.AUTO_CONNECT_LAST_SESSION -> {
                    if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !commManager.isConnected) {
                        hasAttemptedAutoConnect = true
                        attemptAutoConnect()
                    }
                }
                Settings.AUTO_CONNECT_SELF_MODE -> {
                    if (appSettings.autoStartSelfMode && !hasAutoStarted && !commManager.isConnected) {
                        hasAutoStarted = true
                        startSelfMode()
                    }
                }
                Settings.AUTO_CONNECT_SINGLE_USB -> {
                    if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect && !commManager.isConnected) {
                        hasAttemptedSingleUsbAutoConnect = true
                        attemptSingleUsbAutoConnect()
                    }
                }
            }
        }
    }

    private fun startSelfModeInternal() {
        AapService.selfMode = true
        val intent = Intent(requireContext(), AapService::class.java)
        intent.action = AapService.ACTION_START_SELF_MODE
        ContextCompat.startForegroundService(requireContext(), intent)
        AppLog.i("Auto start selfmode")
    }

    private fun startSelfMode() {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else null

        if (activeNetwork == null && VpnControl.isVpnAvailable()) {
            AppLog.i("Device is offline. Preparing Dummy VPN for Self Mode.")
            val vpnIntent = VpnService.prepare(requireContext())
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
                return
            } else {
                AppLog.i("VPN permission already granted. Starting VPN service.")
                VpnControl.startVpn(requireContext());
            }
        } else if (activeNetwork == null) {
            AppLog.i("Device is offline and VPN is not available in this build. Self Mode may fail.")
        }
        startSelfModeInternal()
    }

    private fun attemptAutoConnect() {
        val appSettings = App.provide(requireContext()).settings

        if (!appSettings.autoConnectLastSession ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) {
            return
        }

        val connectionType = appSettings.lastConnectionType
        if (connectionType.isEmpty()) {
            AppLog.i("Auto-connect: No last session to reconnect to")
            return
        }

        when (connectionType) {
            Settings.CONNECTION_TYPE_WIFI -> {
                val ip = appSettings.lastConnectionIp
                if (ip.isNotEmpty()) {
                    AppLog.i("Auto-connect: Attempting WiFi connection to $ip")
                    Toast.makeText(requireContext(), getString(R.string.auto_connecting_to, ip), Toast.LENGTH_SHORT).show()
                    val ctx = requireContext()
                    lifecycleScope.launch(Dispatchers.IO) { App.provide(ctx).commManager.connect(ip, 5277) }
                    ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_CONNECT_SOCKET
                    })
                }
            }
            Settings.CONNECTION_TYPE_USB -> {
                val lastUsbDevice = appSettings.lastConnectionUsbDevice
                if (lastUsbDevice.isNotEmpty()) {
                    val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                    val matchingDevice = usbManager.deviceList.values.find { device ->
                        UsbDeviceCompat.getUniqueName(device) == lastUsbDevice
                    }
                    if (matchingDevice != null && usbManager.hasPermission(matchingDevice)) {
                        AppLog.i("Auto-connect: Attempting USB connection to $lastUsbDevice")
                        Toast.makeText(requireContext(), getString(R.string.auto_connecting_usb), Toast.LENGTH_SHORT).show()
                        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_CHECK_USB
                        })
                    } else {
                        AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
                    }
                }
            }
        }
    }

    private fun attemptSingleUsbAutoConnect() {
        val appSettings = App.provide(requireContext()).settings
        if (!appSettings.autoConnectSingleUsbDevice ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) return

        AppLog.i("HomeFragment: Requesting single-USB auto-connect via AapService")
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
    }

    private fun updatePanelStates(state: ConnectionState) {
        // Map ConnectionState to GlassState and status text
        val (glassState, statusText) = when (state) {
            is ConnectionState.Disconnected -> GlassState.IDLE to getString(R.string.cd_status_not_connected)
            is ConnectionState.Connecting -> GlassState.ACTIVE to getString(R.string.cd_status_connecting)
            is ConnectionState.Connected,
            is ConnectionState.StartingTransport -> GlassState.ACTIVE to getString(R.string.cd_status_connected)
            is ConnectionState.HandshakeComplete,
            is ConnectionState.TransportStarted -> GlassState.READY to (state.toString().let { if (state is ConnectionState.HandshakeComplete) state.deviceName ?: getString(R.string.cd_status_ready) else getString(R.string.cd_status_ready) })
            is ConnectionState.Error -> GlassState.ERROR to getString(R.string.cd_status_error, state.message)
        }

        // Apply to panels (logic: only Self Mode shows specific state for now, 
        // USB/WiFi show state when they are the active transport)
        selfModePanel.setGlassState(glassState)
        selfModeStatus.text = statusText
        
        // Dynamic content descriptions for a11y
        selfModePanel.contentDescription = getString(R.string.cd_self_mode_panel, statusText)
        usbPanel.contentDescription = getString(R.string.cd_usb_panel, if (state is ConnectionState.Disconnected) getString(R.string.cd_status_not_connected) else statusText)
        wifiPanel.contentDescription = getString(R.string.cd_wifi_panel, if (state is ConnectionState.Disconnected) getString(R.string.cd_status_not_connected) else statusText)
    }

    private fun updateAudioStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = requireContext().getSystemService<AudioManager>()
            val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // Simplistic heuristic for active output
            val activeOutput = outputs?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val outputName = when (activeOutput?.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headphones"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speakers"
                else -> "Default"
            }
            audioOutputText.text = getString(R.string.cd_audio_output, outputName)
        } else {
            audioOutputText.text = "Audio: System"
        }
        
        // Mic status from CommManager if available
        val micSource = "Built-in" // Placeholder for now, real status comes via listener in Phase 3
        micStatusText.text = getString(R.string.cd_mic_source, micSource)
    }

    private fun setupListeners() {
        exitButton.setOnClickListener {
            val appSettings = App.provide(requireContext()).settings
            val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_STOP_SERVICE
            }
            ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            requireActivity().finishAffinity()
        }

        selfModePanel.setOnClickListener {
            if (commManager.isConnected) {
                val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                startSelfMode()
            }
        }

        usbPanel.setOnClickListener {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_usbListFragment)
            }
        }

        settingsGear.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        settingsPanel.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        wifiPanel.setOnClickListener {
            val mode = App.provide(requireContext()).settings.wifiConnectionMode
            when (mode) {
                1 -> { // Auto (Headunit Server)
                    if (!commManager.isConnected) {
                        val intent = Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS_SCAN
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
                    }
                }
                2 -> { // Helper
                    if (!commManager.isConnected) {
                        val strategy = App.provide(requireContext()).settings.helperConnectionStrategy
                        if (strategy == 2) showNearbyDeviceSelector()
                        else {
                            val intent = Intent(requireContext(), AapService::class.java).apply {
                                action = AapService.ACTION_START_WIRELESS_SCAN
                            }
                            ContextCompat.startForegroundService(requireContext(), intent)
                        }
                    }
                }
                3 -> showNativeAaDeviceSelector()
                else -> {
                    val controller = findNavController()
                    if (controller.currentDestination?.id == R.id.homeFragment) {
                        controller.navigate(R.id.action_homeFragment_to_networkListFragment)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("HomeFragment: onResume. isConnected=${commManager.isConnected}")
        updateAudioStatus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = requireContext().getSystemService<AudioManager>()
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = requireContext().getSystemService<AudioManager>()
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        }
    }

    private fun showNativeAaDeviceSelector() {
...
        val adapter = if (Build.VERSION.SDK_INT >= 18) {
            (requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(requireContext(), getString(R.string.bt_not_enabled), Toast.LENGTH_SHORT).show()
            return
        }

        val bondedDevices = adapter.bondedDevices?.toList() ?: emptyList()
        if (bondedDevices.isEmpty()) {
            Toast.makeText(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = bondedDevices.map { it.name ?: "Unknown Device" }.toTypedArray()
        
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.select_bt_device)
            .setItems(deviceNames) { _, which ->
                val device = bondedDevices[which]
                AppLog.i("HomeFragment: Manually selected ${device.name} for Native-AA poke")
                
                val intent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_NATIVE_AA_POKE
                    putExtra(AapService.EXTRA_MAC, device.address)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
                Toast.makeText(requireContext(), "Searching for ${device.name}...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNearbyDeviceSelector() {
        // Ensure NearbyManager discovery is running via AapService
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS_SCAN
            })

        val listAdapter = ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1)
        var collectJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(getString(R.string.select_nearby_device))
            .setAdapter(listAdapter) { _, which ->
                val endpoints = NearbyManager.discoveredEndpoints.value
                if (which < endpoints.size) {
                    val endpoint = endpoints[which]
                    AppLog.i("HomeFragment: Selected Nearby device: ${endpoint.name} (${endpoint.id})")
                    val intent = Intent(requireContext(), AapService::class.java).apply {
                        action = AapService.ACTION_NEARBY_CONNECT
                        putExtra(AapService.EXTRA_ENDPOINT_ID, endpoint.id)
                    }
                    ContextCompat.startForegroundService(requireContext(), intent)
                    Toast.makeText(requireContext(), getString(R.string.connecting_to_nearby, endpoint.name), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { collectJob?.cancel() }
            .create()

        dialog.show()

        // Live-update the dialog list as endpoints are discovered
        collectJob = viewLifecycleOwner.lifecycleScope.launch {
            NearbyManager.discoveredEndpoints.collect { endpoints ->
                listAdapter.clear()
                endpoints.forEach { listAdapter.add(it.name) }
                listAdapter.notifyDataSetChanged()
                dialog.setTitle(
                    if (endpoints.isEmpty()) getString(R.string.searching) + "…"
                    else getString(R.string.select_nearby_device) + " (${endpoints.size})"
                )
            }
        }
    }

    companion object {
        private var hasAutoStarted = false
        fun resetAutoStart() {
            hasAutoStarted = false
        }
    }
}