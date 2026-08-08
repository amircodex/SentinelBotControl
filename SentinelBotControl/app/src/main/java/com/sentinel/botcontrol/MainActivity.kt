package com.sentinel.botcontrol

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sentinel.botcontrol.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bt: BluetoothConnectionManager

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val uiHandler = Handler(Looper.getMainLooper())

    private enum class LogType { INFO, SUCCESS, WARNING, ERROR }

    // last known sensor state, used to drive the mini status cards + radar pill
    private var lastDistanceCm: Int? = null
    private var flameActive = false
    private var connectedDeviceName: String? = null
    private var lastSelectedModeButton: View? = null

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            showDevicePicker()
        } else {
            toast("برای اتصال بلوتوث نیاز به مجوز دسترسی است")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bt = BluetoothConnectionManager(
            onStatusChanged = ::handleStatusChange,
            onLineReceived = ::handleIncomingLine
        )

        binding.connectionCard.setOnClickListener {
            if (bt.isConnected) {
                bt.disconnect()
            } else {
                ensurePermissionsThenPick()
            }
        }

        binding.menuButton.setOnClickListener { toast(getString(R.string.nav_coming_soon)) }
        binding.settingsButton.setOnClickListener { toast(getString(R.string.nav_coming_soon)) }
        binding.navControl.setOnClickListener { toast(getString(R.string.nav_coming_soon)) }
        binding.navSensors.setOnClickListener { toast(getString(R.string.nav_coming_soon)) }
        binding.navSettings.setOnClickListener { toast(getString(R.string.nav_coming_soon)) }

        binding.alertBanner.setOnClickListener {
            send('k')
            hideAlert()
        }

        binding.autoModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) send('u') else send('v')
        }

        bindModeButtons()
        bindMovementButtons()
        setControlsEnabled(false)
        startLogCleanupLoop()
        updateRadarStatusPill()
        updateSensorCards()
        rebuildLogText()
    }

    // ---------------------------------------------------------------
    // Bluetooth permission + device selection
    // ---------------------------------------------------------------

    private fun ensurePermissionsThenPick() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_SCAN
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            showDevicePicker()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        if (!bt.isBluetoothEnabled()) {
            toast("لطفاً ابتدا بلوتوث گوشی را روشن کنید")
            return
        }
        val devices = bt.getBondedDevices()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_pick_device_title))
                .setMessage(getString(R.string.dialog_no_devices))
                .setPositiveButton("باشه", null)
                .show()
            return
        }
        val names = devices.map { "${it.name}\n${it.address}" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_pick_device_title))
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names)) { _, which ->
                connectTo(devices[which])
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        connectedDeviceName = device.name ?: device.address
        bt.connect(device)
    }

    // ---------------------------------------------------------------
    // Status + incoming data handling
    // ---------------------------------------------------------------

    private fun handleStatusChange(status: BluetoothConnectionManager.Status) {
        when (status) {
            BluetoothConnectionManager.Status.DISCONNECTED -> {
                binding.statusText.text = getString(R.string.status_disconnected)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                binding.deviceNameText.text = getString(R.string.connection_hint_tap)
                binding.statusCircleBg.background.setTint(ContextCompat.getColor(this, R.color.mode_danger_bg))
                binding.statusGlow.background.setTint(ContextCompat.getColor(this, R.color.accent_red))
                setControlsEnabled(false)
                lastDistanceCm = null
                flameActive = false
                binding.radarView.clearData()
                updateRadarStatusPill()
                updateSensorCards()
            }
            BluetoothConnectionManager.Status.CONNECTING -> {
                binding.statusText.text = getString(R.string.status_connecting)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                binding.statusCircleBg.background.setTint(ContextCompat.getColor(this, R.color.mode_lowbat_bg))
                binding.statusGlow.background.setTint(ContextCompat.getColor(this, R.color.accent_amber))
                setControlsEnabled(false)
            }
            BluetoothConnectionManager.Status.CONNECTED -> {
                binding.statusText.text = getString(R.string.status_connected)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.statusCircleBg.background.setTint(ContextCompat.getColor(this, R.color.mode_rescue_bg))
                binding.statusGlow.background.setTint(ContextCompat.getColor(this, R.color.accent_green))
                setControlsEnabled(true)
                appendLog("اتصال با HC-05 برقرار شد", LogType.SUCCESS)
            }
            BluetoothConnectionManager.Status.FAILED -> {
                binding.statusText.text = getString(R.string.status_disconnected)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                binding.deviceNameText.text = getString(R.string.connection_hint_tap)
                binding.statusCircleBg.background.setTint(ContextCompat.getColor(this, R.color.mode_danger_bg))
                binding.statusGlow.background.setTint(ContextCompat.getColor(this, R.color.accent_red))
                setControlsEnabled(false)
                toast("اتصال ناموفق بود")
            }
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        if (enabled) {
            binding.deviceNameText.text = connectedDeviceName ?: "HC-05"
        }
        val alpha = if (enabled) 1f else 0.4f
        listOf(
            binding.btnNormal, binding.btnHappy, binding.btnSad, binding.btnAngry,
            binding.btnGuard, binding.btnRescue, binding.btnFriendly, binding.btnDanger,
            binding.btnLowbat, binding.btnStuck,
            binding.btnForward, binding.btnBackward, binding.btnLeft, binding.btnRight, binding.btnStop,
            binding.autoModeSwitch
        ).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
    }

    /**
     * Parses lines coming from the firmware:
     *   RADAR:<angle>,<distance>
     *   STATE:<label>
     *   FIRE! Angle: <a> Dist: <d>
     *   ALARM CLEARED / AUTO START / MOTOR FREED / MOTOR STILL STUCK... / HELP! ...
     */
    // Matches a RADAR payload even if noise on the Bluetooth link corrupted the
    // "RADAR:" prefix (e.g. a dropped byte turns it into "ADAR:90,42" or leaves a
    // bare "90,42" fragment) — anything shaped like <angle>,<distance> is treated
    // as radar telemetry and never shown in the log.
    private val radarFragmentPattern = Regex("(\\d{1,3}),(\\d{1,3})")

    private fun handleIncomingLine(line: String) {
        if (line.contains("RADAR:") || (line.length <= 12 && radarFragmentPattern.containsMatchIn(line) && !line.any { it.isLetter() && it !in "RADAR" })) {
            val match = radarFragmentPattern.find(line)
            if (match != null) {
                val angle = match.groupValues[1].toIntOrNull()
                val dist = match.groupValues[2].toIntOrNull()
                if (angle != null && dist != null) {
                    binding.radarView.updateReading(angle, dist)
                    lastDistanceCm = if (dist in 1 until 400) dist else null
                    updateRadarStatusPill()
                    updateSensorCards()
                }
            }
            return
        }

        when {
            line.startsWith("BATTERY:") -> {
                val value = line.removePrefix("BATTERY:").trim()
                binding.batteryText.text = if (value.isNotEmpty()) "$value%" else "—"
            }
            line.startsWith("STATE:") -> {
                val label = line.removePrefix("STATE:")
                handleStateLabel(label)
            }
            line.startsWith("FIRE!") -> {
                flameActive = true
                updateSensorCards()
                appendLog("آتش شناسایی شد!", LogType.ERROR)
                showAlert("⚠", "خطر! آتش یا نفوذ تشخیص داده شد", R.color.mode_danger)
            }
            line.contains("HELP!") -> {
                appendLog("فرد شناسایی شد - در انتظار دستور", LogType.WARNING)
                showAlert("🧍", "فرد شناسایی شد", R.color.mode_guard)
            }
            line.contains("ALARM CLEARED") -> {
                flameActive = false
                updateSensorCards()
                appendLog("هشدار پاک شد", LogType.SUCCESS)
            }
            line.contains("MOTOR STILL STUCK") -> {
                appendLog("موتور همچنان گیر است - نیاز به کمک دستی", LogType.ERROR)
            }
            line.contains("MOTOR FREED") -> {
                appendLog("ربات آزاد شد", LogType.SUCCESS)
            }
            line.contains("AUTO START") -> appendLog("حالت خودکار فعال شد", LogType.INFO)
            else -> appendLog(line, LogType.INFO)
        }
    }

    private fun handleStateLabel(label: String) {
        when (label) {
            "DANGER" -> {
                appendLog("وضعیت: خطر", LogType.ERROR)
                showAlert("⚠", "خطر! آتش یا نفوذ تشخیص داده شد", R.color.mode_danger)
            }
            "STUCK" -> {
                appendLog("وضعیت: گیر کردن", LogType.WARNING)
                showAlert("⛔", "ربات گیر کرده است", R.color.mode_stuck)
            }
            "EDGE_WARNING" -> {
                appendLog("وضعیت: نزدیک لبه", LogType.WARNING)
                showAlert("⬇", "هشدار! نزدیک لبه", R.color.accent_amber)
            }
            "NORMAL" -> {
                flameActive = false
                updateSensorCards()
                appendLog("وضعیت به حالت عادی برگشت", LogType.SUCCESS)
            }
            else -> appendLog("وضعیت: $label", LogType.INFO)
        }
    }

    // ---------------------------------------------------------------
    // Radar status pill + sensor mini-cards
    // ---------------------------------------------------------------

    private fun updateRadarStatusPill() {
        val dist = lastDistanceCm
        when {
            !bt.isConnected || dist == null -> {
                binding.radarStatusIcon.text = "❗"
                binding.radarStatusText.text = getString(R.string.radar_no_data)
                binding.radarStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            dist < 15 -> {
                binding.radarStatusIcon.text = "⚠"
                binding.radarStatusText.text = getString(R.string.radar_warning)
                binding.radarStatusText.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            }
            else -> {
                binding.radarStatusIcon.text = "✅"
                binding.radarStatusText.text = getString(R.string.radar_safe)
                binding.radarStatusText.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            }
        }
    }

    private fun updateSensorCards() {
        val dist = lastDistanceCm
        binding.sensorDistanceValue.text = if (dist != null) "${dist}cm" else "--"

        if (dist != null) {
            val obstacleClose = dist < 15
            binding.sensorObstacleValue.text = if (obstacleClose) "بله" else "خیر"
            binding.sensorObstacleValue.setTextColor(
                ContextCompat.getColor(this, if (obstacleClose) R.color.accent_red else R.color.accent_green)
            )
        } else {
            binding.sensorObstacleValue.text = "--"
            binding.sensorObstacleValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }

        if (bt.isConnected) {
            binding.sensorFlameValue.text = if (flameActive) "آتش!" else "خیر"
            binding.sensorFlameValue.setTextColor(
                ContextCompat.getColor(this, if (flameActive) R.color.accent_red else R.color.accent_green)
            )
        } else {
            binding.sensorFlameValue.text = "--"
            binding.sensorFlameValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
    }

    // ---------------------------------------------------------------
    // Animated on-screen sensor alert banner
    // ---------------------------------------------------------------

    private val alertHideRunnable = Runnable { hideAlert() }

    private fun showAlert(icon: String, text: String, colorRes: Int) {
        binding.alertIcon.text = icon
        binding.alertText.text = text
        binding.alertBanner.setBackgroundColor(ContextCompat.getColor(this, colorRes))

        uiHandler.removeCallbacks(alertHideRunnable)

        if (binding.alertBanner.visibility != View.VISIBLE) {
            binding.alertBanner.visibility = View.VISIBLE
            binding.alertBanner.translationY = -binding.alertBanner.height.toFloat().coerceAtLeast(160f)
            binding.alertBanner.alpha = 0f
            binding.alertBanner.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(260)
                .start()
        }

        uiHandler.postDelayed(alertHideRunnable, 5000)
    }

    private fun hideAlert() {
        uiHandler.removeCallbacks(alertHideRunnable)
        binding.alertBanner.animate()
            .translationY(-binding.alertBanner.height.toFloat().coerceAtLeast(160f))
            .alpha(0f)
            .setDuration(220)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.alertBanner.visibility = View.GONE
                }
            })
            .start()
    }

    // ---------------------------------------------------------------
    // Log console — colored entries, auto-expires after 60 seconds so the
    // report never grows unbounded during a long run.
    // ---------------------------------------------------------------

    private data class LogEntry(val time: Long, val text: String, val type: LogType)

    private val logEntries = ArrayDeque<LogEntry>()
    private val logRetentionMs = 60_000L
    private val logCleanupIntervalMs = 5_000L

    private val logCleanupRunnable = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - logRetentionMs
            var changed = false
            while (logEntries.isNotEmpty() && logEntries.first().time < cutoff) {
                logEntries.removeFirst()
                changed = true
            }
            if (changed) rebuildLogText()
            uiHandler.postDelayed(this, logCleanupIntervalMs)
        }
    }

    private fun startLogCleanupLoop() {
        uiHandler.postDelayed(logCleanupRunnable, logCleanupIntervalMs)
    }

    private fun appendLog(text: String, type: LogType) {
        logEntries.addLast(LogEntry(System.currentTimeMillis(), text, type))
        rebuildLogText()
    }

    private fun typeColor(type: LogType): Int = when (type) {
        LogType.INFO -> R.color.log_info
        LogType.SUCCESS -> R.color.log_success
        LogType.WARNING -> R.color.log_warning
        LogType.ERROR -> R.color.log_error
    }

    private fun typeIcon(type: LogType): String = when (type) {
        LogType.INFO -> "i"
        LogType.SUCCESS -> "✓"
        LogType.WARNING -> "!"
        LogType.ERROR -> "✕"
    }

    private val inflater by lazy { LayoutInflater.from(this) }

    private fun rebuildLogText() {
        if (logEntries.isEmpty()) {
            binding.logEmptyState.visibility = View.VISIBLE
            binding.logScroll.visibility = View.GONE
            return
        }
        binding.logEmptyState.visibility = View.GONE
        binding.logScroll.visibility = View.VISIBLE

        binding.logContainer.removeAllViews()
        val color = { type: LogType -> ContextCompat.getColor(this, typeColor(type)) }
        for (entry in logEntries) {
            val row = inflater.inflate(R.layout.item_log_entry, binding.logContainer, false)
            row.findViewById<View>(R.id.logBar).background.setTint(color(entry.type))
            row.findViewById<TextView>(R.id.logTime).text = timeFmt.format(entry.time)
            row.findViewById<TextView>(R.id.logMessage).text = entry.text
            row.findViewById<View>(R.id.logBadgeBg).background.setTint(color(entry.type))
            row.findViewById<View>(R.id.logBadgeGlow).background.setTint(color(entry.type))
            row.findViewById<TextView>(R.id.logBadgeIcon).text = typeIcon(entry.type)
            binding.logContainer.addView(row)
        }
        binding.logScroll.post { binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------
    // Button wiring -> single-character serial commands (matches firmware)
    // ---------------------------------------------------------------

    private fun bindModeButtons() {
        binding.btnNormal.setOnClickListener { send('n'); selectModeButton(binding.btnNormal, "بازیگوش") }
        binding.btnHappy.setOnClickListener { send('h'); selectModeButton(binding.btnHappy, "خوشحال") }
        binding.btnSad.setOnClickListener { send('s'); selectModeButton(binding.btnSad, "غمگین") }
        binding.btnAngry.setOnClickListener { send('a'); selectModeButton(binding.btnAngry, "عصبی") }
        binding.btnGuard.setOnClickListener { send('g'); selectModeButton(binding.btnGuard, "نکته‌دان") }
        binding.btnRescue.setOnClickListener { send('r'); selectModeButton(binding.btnRescue, "نجات") }
        binding.btnFriendly.setOnClickListener { send('f'); selectModeButton(binding.btnFriendly, "دوستانه") }
        binding.btnDanger.setOnClickListener { send('d'); selectModeButton(binding.btnDanger, "هشدار خطر") }
        binding.btnLowbat.setOnClickListener { send('y'); selectModeButton(binding.btnLowbat, "خواب‌آلود") }
        binding.btnStuck.setOnClickListener { send('m'); selectModeButton(binding.btnStuck, "ترس") }
    }

    private fun selectModeButton(button: View, label: String) {
        lastSelectedModeButton?.foreground = null
        button.foreground = ContextCompat.getDrawable(this, R.drawable.bg_mode_ring)
        lastSelectedModeButton = button
        binding.currentModeText.text = "حالت: $label"
    }

    private fun bindMovementButtons() {
        binding.btnForward.setOnClickListener { send('i') }
        binding.btnBackward.setOnClickListener { send('b') }
        // NOTE: swapped on purpose — on this robot's wiring, firmware command 'j'
        // physically turns the robot right and 'l' turns it left, opposite of the
        // names in the Arduino code. Mapping the buttons this way makes the
        // on-screen ◀ / ▶ arrows match the robot's actual movement.
        binding.btnLeft.setOnClickListener { send('l') }
        binding.btnRight.setOnClickListener { send('j') }
        binding.btnStop.setOnClickListener { send('t') }
    }

    private fun send(command: Char) {
        if (!bt.isConnected) {
            toast("ابتدا به ربات متصل شوید")
            return
        }
        bt.sendCommand(command)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        bt.disconnect()
    }
}
