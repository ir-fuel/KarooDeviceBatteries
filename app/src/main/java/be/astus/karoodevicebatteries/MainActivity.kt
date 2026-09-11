package be.astus.karoodevicebatteries

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import be.astus.karoodevicebatteries.databinding.ActivityMainBinding
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var karooSystem: KarooSystemService
    private lateinit var batteryStore: BatteryStore
    private lateinit var appConfig: AppConfig
    private var devicesConsumerId: String? = null
    private var batteryConsumerId: String? = null
    private var lastDevices: List<SavedDevices.SavedDevice> = emptyList()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percentage = (level * 100 / scale.toFloat()).toInt()
            batteryStore.savePercentage("karoo_internal", percentage)
            renderDeviceList(lastDevices)
        }
    }

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        karooSystem = KarooSystemService(this)
        batteryStore = BatteryStore(this)
        appConfig = AppConfig(this)

        setupSettings()
        DiagnosticLog.init(this)
        startHomeWifiService()
    }

    private fun startHomeWifiService() {
        ContextCompat.startForegroundService(this, Intent(this, HomeWifiForegroundService::class.java))
    }

    private fun setupSettings() {
        binding.mqttHostEditText.setText(appConfig.mqttHost)
        binding.mqttUsernameEditText.setText(appConfig.mqttUsername)
        binding.mqttPasswordEditText.setText(appConfig.mqttPassword)
        binding.homeSsidEditText.setText(appConfig.homeSsid)
        binding.ignoredDeviceIdsEditText.setText(appConfig.ignoredDeviceIds)

        binding.toggleSettingsButton.setOnClickListener {
            binding.settingsContainer.visibility = if (binding.settingsContainer.visibility == View.VISIBLE) {
                View.GONE
            } else {
                View.VISIBLE
            }
        }

        binding.saveSettingsButton.setOnClickListener {
            appConfig.mqttHost = binding.mqttHostEditText.text.toString()
            appConfig.mqttUsername = binding.mqttUsernameEditText.text.toString()
            appConfig.mqttPassword = binding.mqttPasswordEditText.text.toString()
            appConfig.homeSsid = binding.homeSsidEditText.text.toString()
            appConfig.ignoredDeviceIds = binding.ignoredDeviceIdsEditText.text.toString()
            binding.statusTextView.text = "Settings saved"
            renderDeviceList(lastDevices)

            binding.settingsContainer.visibility = View.GONE
            hideKeyboard()

            requestLocationPermissions()

            // The home SSID may have just been set while already connected to it - there's
            // no new connectivity event in that case, so ask the running service to check now.
            ContextCompat.startForegroundService(this, HomeWifiForegroundService.recheckIntent(this))
        }

        binding.syncNowButton.setOnClickListener {
            DiagnosticLog.i("Manual 'Sync Now' triggered")
            val workRequest = OneTimeWorkRequestBuilder<MqttWorker>().build()
            WorkManager.getInstance(this).enqueue(workRequest)
            binding.statusTextView.text = "Sync triggered..."
            binding.settingsContainer.visibility = View.GONE
            hideKeyboard()
        }

        binding.refreshBatteriesButton.setOnClickListener {
            observeSavedDevices()
            binding.statusTextView.text = "Refreshing battery levels..."
            binding.settingsContainer.visibility = View.GONE
            hideKeyboard()
        }

        binding.viewLogsButton.setOnClickListener {
            showLogsDialog()
        }
    }

    private fun showLogsDialog() {
        val textView = TextView(this).apply {
            text = DiagnosticLog.readAll()
            setTextColor(Color.LTGRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(24, 24, 24, 24)
            typeface = Typeface.MONOSPACE
        }
        val scrollView = ScrollView(this).apply { addView(textView) }

        AlertDialog.Builder(this)
            .setTitle("Sync Logs")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                DiagnosticLog.clear()
            }
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun requestLocationPermissions() {
        // Reading the connected Wi-Fi SSID from the background WorkManager job requires
        // ACCESS_BACKGROUND_LOCATION on API 29+, in addition to fine/coarse location.
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(permissions.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            checkBackgroundLocationHint()
        }
    }

    private fun checkBackgroundLocationHint() {
        // On API 30+, ACCESS_BACKGROUND_LOCATION cannot be granted from the same runtime
        // dialog as foreground location - the user has to flip "Allow all the time" in
        // system Settings. Home Wi-Fi detection silently never matches without it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val hasBackgroundLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasBackgroundLocation) {
            binding.statusTextView.text =
                "Home Wi-Fi sync needs \"Allow all the time\" Location access. Tap here to open Settings."
            binding.statusTextView.setTextColor(Color.YELLOW)
            binding.statusTextView.setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
        } else {
            binding.statusTextView.setOnClickListener(null)
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        startHomeWifiService()
        karooSystem.connect { connected ->
            runOnUiThread {
                if (connected) {
                    binding.statusTextView.text = "Status: Connected to Karoo System"
                    observeSavedDevices()
                    observeBatteries()
                    checkBackgroundLocationHint()
                } else {
                    binding.statusTextView.text = "Status: Failed to connect"
                    binding.statusTextView.setTextColor(Color.RED)
                }
            }
        }
    }

    override fun onStop() {
        unregisterReceiver(batteryReceiver)
        devicesConsumerId?.let { karooSystem.removeConsumer(it) }
        batteryConsumerId?.let { karooSystem.removeConsumer(it) }
        karooSystem.disconnect()
        super.onStop()
    }

    private fun observeBatteries() {
        batteryConsumerId?.let { karooSystem.removeConsumer(it) }
        batteryConsumerId = karooSystem.addConsumer<OnStreamState>(
            params = OnStreamState.StartStreaming(DataType.Type.BATTERY_PERCENT),
            onEvent = { event ->
                val streaming = event.state as? StreamState.Streaming
                val percentage = streaming?.dataPoint?.singleValue?.toInt()
                val sourceId = streaming?.dataPoint?.sourceId

                if (percentage != null) {
                    val storeId = if (sourceId == null || sourceId == "internal") "karoo_internal" else sourceId
                    batteryStore.savePercentage(storeId, percentage)
                    runOnUiThread {
                        renderDeviceList(lastDevices)
                    }
                }
            }
        )
    }

    private fun observeSavedDevices() {
        devicesConsumerId?.let { karooSystem.removeConsumer(it) }
        devicesConsumerId = karooSystem.addConsumer<SavedDevices>(
            onEvent = { event ->
                DiagnosticLog.i(
                    "MainActivity: SavedDevices event, ${event.devices.size} device(s): " +
                        event.devices.joinToString { "[id=${it.id} name='${it.name}']" }
                )
                runOnUiThread {
                    lastDevices = event.devices
                    renderDeviceList(event.devices)
                }
            },
            onError = { error ->
                runOnUiThread {
                    binding.statusTextView.text = "Error: $error"
                    binding.statusTextView.setTextColor(Color.RED)
                }
            }
        )
    }

    private fun renderDeviceList(allDevices: List<SavedDevices.SavedDevice>) {
        binding.sensorContainer.removeAllViews()
        val devices = allDevices.filter { !appConfig.isDeviceIgnored(it.id) }

        // Manual entry for Karoo Internal Battery
        val karooPercentage = batteryStore.getPercentage("karoo_internal")
        if (karooPercentage != -1) {
            addSensorCard(
                name = "Karoo Device",
                manufacturer = "Hammerhead",
                percentage = karooPercentage,
                status = BatteryStatus.fromPercentage(karooPercentage),
                lastUpdate = System.currentTimeMillis(),
                isInternal = true
            )
        }

        if (devices.isEmpty() && karooPercentage == -1) {
            val emptyTv = TextView(this).apply {
                text = "No paired sensors found.\nPair devices in Karoo Settings first."
                setTextColor(Color.GRAY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(0, 40, 0, 0)
            }
            binding.sensorContainer.addView(emptyTv)
            binding.statusTextView.text = "Status: Connected (No devices)"
            return
        }

        for (device in devices) {
            val detail = device.details
            addSensorCard(
                name = device.name,
                manufacturer = detail.manufacturer ?: "Generic",
                percentage = batteryStore.getPercentage(device.id),
                status = detail.lastBattery,
                lastUpdate = detail.lastBatteryUpdate,
                deviceId = device.id
            )
        }

        binding.statusTextView.text = "Status: Connected. Watching ${devices.size} sensors."
    }

    private fun addSensorCard(
        name: String,
        manufacturer: String,
        percentage: Int,
        status: BatteryStatus?,
        lastUpdate: Long?,
        isInternal: Boolean = false,
        deviceId: String? = null
    ) {
        val sensorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 8)
            }
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(16, 12, 16, 12)
            if (deviceId != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId))
                    Toast.makeText(this@MainActivity, "Copied device id: $deviceId", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val nameTv = TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val batteryColor = when (status) {
            BatteryStatus.NEW, BatteryStatus.GOOD -> Color.GREEN
            BatteryStatus.OK -> Color.YELLOW
            BatteryStatus.LOW, BatteryStatus.CRITICAL -> Color.RED
            else -> Color.GRAY
        }

        val batteryIconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_battery)
            setColorFilter(batteryColor)
            layoutParams = LinearLayout.LayoutParams(18.dpToPx(), 18.dpToPx()).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(0, 0, 6, 0)
            }
        }

        val batteryTv = TextView(this).apply {
            val rawStatusText = status?.name ?: "UNKNOWN"
            val statusText = when (rawStatusText.uppercase()) {
                "NEW" -> "Full"
                "GOOD" -> "High"
                "OK" -> "Medium"
                "LOW" -> "Low"
                "CRITICAL" -> "Critical"
                else -> rawStatusText
            }

            text = if (isInternal && percentage != -1) {
                "$percentage% ($statusText)"
            } else {
                statusText
            }

            setTextColor(batteryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
        }
        row1.addView(nameTv)
        row1.addView(batteryIconView)
        row1.addView(batteryTv)

        val row2 = TextView(this).apply {
            val dateStr = lastUpdate?.let { dateFormat.format(Date(it)) } ?: "Never"
            text = "$manufacturer • Last measured: $dateStr"
            setTextColor(Color.parseColor("#888888"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }

        sensorLayout.addView(row1)
        sensorLayout.addView(row2)

        if (deviceId != null) {
            val row3 = TextView(this).apply {
                text = "id: $deviceId (tap to copy)"
                setTextColor(Color.parseColor("#555555"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            }
            sensorLayout.addView(row3)
        }

        binding.sensorContainer.addView(sensorLayout)
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()
}
