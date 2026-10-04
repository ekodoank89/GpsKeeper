package com.example.gpskeeper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var dataText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnBackground: Button
    private lateinit var btnBattery: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            Toast.makeText(
                this,
                if (granted) "Izin lokasi diberikan ✅" else "Izin lokasi ditolak ❌",
                Toast.LENGTH_SHORT
            ).show()
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (!hasLocationPermission()) askPermissions()
        observeLocation()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun observeLocation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { LocationStore.running.collect { refreshUi() } }
                launch { LocationStore.location.collect { showLocation(it) } }
            }
        }
    }

    private fun showLocation(loc: Location?) {
        if (loc == null) {
            dataText.text = "Menunggu fix GPS…\n(Pastikan lokasi HP aktif dan berada di area terbuka)"
        } else {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(loc.time))
            dataText.text = String.format(
                Locale.US,
                "Latitude : %.6f\nLongitude: %.6f\nAkurasi  : ±%.1f m\nKecepatan: %.1f m/s\nUpdate   : %s",
                loc.latitude, loc.longitude, loc.accuracy, loc.speed, time
            )
        }
    }

    private fun buildUi(): View {
        val pad = dp(20)

        val title = TextView(this).apply {
            text = "📡 GPS Keeper"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Menjaga GPS tetap aktif agar lokasi selalu terbaca."
            textSize = 14f
        }
        statusText = TextView(this).apply {
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
        dataText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF37474F.toInt())
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(12))
        }
        dataText.text = "Menunggu fix GPS…"

        btnStart = makeButton("▶  Mulai GPS") { startGps() }
        btnStop = makeButton("⏹  Berhenti") { stopGps() }
        btnBackground = makeButton("🔓  Aktifkan lokasi \"All the time\"") { openAppSettings() }
        btnBattery = makeButton("🔋  Pengecualian baterai") { requestIgnoreBattery() }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun add(view: View, topMargin: Int = dp(8)) {
            column.addView(
                view, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = topMargin })
        }

        column.addView(title)
        column.addView(subtitle)
        add(statusText, 0)
        add(dataText, 0)
        add(btnStart, dp(16))
        add(btnStop)
        add(btnBackground, dp(24))
        add(btnBattery)

        return ScrollView(this).apply { addView(column) }
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            allCaps = false
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startGps() {
        if (!hasLocationPermission()) {
            askPermissions()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
        refreshUi()
    }

    private fun stopGps() {
        stopService(Intent(this, LocationService::class.java))
        LocationStore.location.value = null
        refreshUi()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Sudah dikecualikan ✅", Toast.LENGTH_SHORT).show()
        } else {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                openAppSettings()
            }
        }
    }

    private fun refreshUi() {
        val running = LocationStore.running.value
        statusText.text = if (running) "Status: 🟢 AKTIF (GPS dijaga)" else "Status: ⚪ BERHENTI"
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
        val needBg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
        btnBackground.visibility = if (needBg) View.VISIBLE else View.GONE
    }
}
