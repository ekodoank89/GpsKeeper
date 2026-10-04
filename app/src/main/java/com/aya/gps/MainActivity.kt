package com.aya.gps

import android.Manifest
import android.app.AlertDialog
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
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var dataText: TextView
    private lateinit var netText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnBackground: Button
    private lateinit var btnBattery: Button
    private lateinit var btnTips: Button
    private lateinit var switchAuto: Switch
    private lateinit var mapView: MapView

    private var googleMap: GoogleMap? = null
    private var marker: Marker? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            Toast.makeText(
                this,
                if (granted) "Izin lokasi diberikan ✅" else "Izin lokasi ditolak ❌",
                Toast.LENGTH_SHORT
            ).show()
            if (granted) {
                try { googleMap?.isMyLocationEnabled = true } catch (_: SecurityException) {}
            }
            refreshUi()
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            doStartService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        mapView.onCreate(null)
        initMap()
        if (!hasLocationPermission()) askPermissions()
        observeLocation()
    }

    // Siklus hidup MapView wajib diteruskan secara manual
    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume(); refreshUi() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onDestroy() { mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    private fun initMap() {
        try { MapsInitializer.initialize(applicationContext) } catch (_: Exception) {}
        mapView.getMapAsync { map ->
            googleMap = map
            map.uiSettings.isZoomControlsEnabled = true
            map.uiSettings.isCompassEnabled = true
            if (hasLocationPermission()) {
                try { map.isMyLocationEnabled = true } catch (_: SecurityException) {}
            }
        }
    }

    private fun updateMap(loc: Location) {
        val map = googleMap ?: return
        val pos = LatLng(loc.latitude, loc.longitude)
        if (marker == null) {
            marker = map.addMarker(
                MarkerOptions()
                    .position(pos)
                    .title("Posisi AYA GPS")
                    .snippet("±%.0f m • %s".format(loc.accuracy, loc.provider))
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
            )
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 17f))
        } else {
            marker?.position = pos
            marker?.snippet = "±%.0f m • %s".format(loc.accuracy, loc.provider)
            map.moveCamera(CameraUpdateFactory.newLatLng(pos))
        }
    }

    private fun observeLocation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { LocationStore.running.collect { refreshUi() } }
                launch { LocationStore.location.collect { showLocation(it) } }
                launch { LocationStore.satellites.collect {
                    if (LocationStore.location.value != null) showLocation(LocationStore.location.value)
                } }
                launch { LocationStore.latencyMs.collect { showNetwork() } }
            }
        }
    }

    private fun showNetwork() {
        val lat = LocationStore.latencyMs.value
        if (lat < 0) {
            netText.text = dotText("Menunggu data jaringan…", 0xFF78909C.toInt())
            return
        }

        val color = when {
            lat < 30 -> 0xFF2E7D32.toInt()    // hijau  : stabil
            lat <= 100 -> 0xFFF9A825.toInt()  // kuning : sedang
            else -> 0xFFC62828.toInt()        // merah  : jelek
        }

        val prefix = String.format(Locale.US, "Jaringan (%s): ", LocationStore.netMode.value)
        val value = String.format(Locale.US, "%d ms", lat)
        val suffix = String.format(
            Locale.US, "  •  jitter %.1f ms  •  loss %d%%",
            LocationStore.jitterMs.value, LocationStore.lossPct.value
        )

        val s = SpannableString("●  $prefix$value$suffix")
        s.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val vStart = 3 + prefix.length
        s.setSpan(ForegroundColorSpan(color), vStart, vStart + value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(StyleSpan(Typeface.BOLD), vStart, vStart + value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        netText.text = s
    }

    private fun dotText(text: String, dotColor: Int): SpannableString {
        val s = SpannableString("●  $text")
        s.setSpan(ForegroundColorSpan(dotColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun showLocation(loc: Location?) {
        if (loc == null) {
            dataText.text = "Memindai semua sumber lokasi…\n(Satelit + Seluler + Wi-Fi)\n\nDi dalam gedung: fix pertama butuh 1–5 menit. Tetap aktif ya!"
        } else {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(loc.time))
            dataText.text = String.format(
                Locale.US,
                "Latitude : %.6f\nLongitude: %.6f\nAkurasi  : ±%.1f m\nSumber   : %s\nKecepatan: %.1f m/s\nSatelit GNSS: %s\nUpdate   : %s",
                loc.latitude, loc.longitude, loc.accuracy, loc.provider,
                loc.speed, LocationStore.satellites.value, time
            )
            updateMap(loc)
        }
    }

    private fun buildUi(): View {
        val pad = dp(20)

        val title = TextView(this).apply {
            text = "📡 AYA GPS — Mode Agresif"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Satelit GNSS + Seluler + Wi-Fi + Passive dipindai bersamaan, tiap 1 detik."
            textSize = 13f
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
            setPadding(0, dp(4), 0, dp(8))
        }
        dataText.text = "Memindai semua sumber lokasi…"
        netText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF37474F.toInt())
            setTextIsSelectable(true)
            setPadding(0, 0, 0, dp(12))
        }
        netText.text = dotText("Menunggu data jaringan…", 0xFF78909C.toInt())

        val mapCaption = TextView(this).apply {
            text = "🗺️ Peta posisi (real-time)"
            textSize = 13f
            setTextColor(0xFF78909C.toInt())
            setPadding(0, dp(4), 0, dp(2))
        }
        mapView = MapView(this).apply {
            // Agar gestur peta tidak "berebut" dengan scroll layar
            setOnTouchListener { v, _ ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                false
            }
        }

        btnStart = makeButton("▶  Mulai GPS (Mode Agresif)") { startGps() }
        btnStop = makeButton("⏹  Berhenti") { stopGps() }
        btnTips = makeButton("🛰️  Tips Sinyal di Tempat Sulit") { showTips() }
        btnBackground = makeButton("🔓  Aktifkan lokasi \"All the time\"") { openAppSettings() }
        btnBattery = makeButton("🔋  Pengecualian baterai") { requestIgnoreBattery() }

        switchAuto = Switch(this).apply {
            text = "🚀 Mulai otomatis saat HP nyala / restart"
            textSize = 15f
            isChecked = getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
                .getBoolean(BootReceiver.KEY_AUTO_START, false)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(BootReceiver.KEY_AUTO_START, checked)
                    .apply()
                if (checked) {
                    Toast.makeText(
                        this@MainActivity,
                        "Auto start AKTIF ✅ Agar pasti berjalan: izinkan lokasi \"All the time\", pengecualian baterai, dan \"Autostart\" (Xiaomi/Oppo/Vivo).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        val autoCaption = TextView(this).apply {
            text = "Setiap kali HP dinyalakan ulang, AYA GPS langsung menjaga GPS tanpa perlu dibuka."
            textSize = 12f
            setTextColor(0xFF78909C.toInt())
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun add(view: View, topMargin: Int = dp(8)) {
            column.addView(
                view, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { this.topMargin = topMargin })
        }

        column.addView(title)
        column.addView(subtitle)
        add(statusText, 0)
        add(dataText, 0)
        add(netText, 0)
        add(mapCaption)
        column.addView(
            mapView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
            )
        )
        add(btnStart, dp(16))
        add(btnStop)
        add(btnTips)
        add(btnBackground, dp(24))
        add(btnBattery)
        add(switchAuto, dp(24))
        add(autoCaption, 0)

        return ScrollView(this).apply { addView(column) }
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setAllCaps(false)
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

    private fun aggressiveRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setWaitForAccurateLocation(false)
            .build()

    private fun startGps() {
        if (!hasLocationPermission()) {
            askPermissions()
            return
        }
        checkSettingsThenStart()
    }

    private fun checkSettingsThenStart() {
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(aggressiveRequest())
            .setAlwaysShow(true)
            .setNeedBle(true)
            .build()
        LocationServices.getSettingsClient(this)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener { doStartService() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    try {
                        settingsLauncher.launch(
                            IntentSenderRequest.Builder(e.resolution).build()
                        )
                    } catch (_: Exception) { doStartService() }
                } else doStartService()
            }
    }

    private fun doStartService() {
        ContextCompat.startForegroundService(this, Intent(this, LocationService::class.java))
        refreshUi()
    }

    private fun stopGps() {
        stopService(Intent(this, LocationService::class.java))
        LocationStore.location.value = null
        LocationStore.satellites.value = "–/–"
        refreshUi()
    }

    private fun showTips() {
        AlertDialog.Builder(this)
            .setTitle("🛰️ Tips Sinyal di Tempat Sulit")
            .setMessage(
                "1. Pastikan Lokasi HP = ON mode \"Precise / High accuracy\".\n\n" +
                "2. Aktifkan \"Google Location Accuracy\" (Setelan Google → Lokasi) + izinkan Pemindaian Wi-Fi & Bluetooth.\n\n" +
                "3. Dalam rumah: dekat jendela; hindari atap beton tebal. Fix pertama butuh 1–5 menit.\n\n" +
                "4. Bawah pohon / gang / gedung tinggi: langit menyempit & sinyal memantul — akurasi ±20–50 m itu normal.\n\n" +
                "5. Pasang SIM aktif: jaringan seluler adalah sumber lokasi utama di dalam ruangan.\n\n" +
                "6. Biarkan AYA GPS menyala — chip yang \"hangat\" dapat fix ulang dalam hitungan detik.\n\n" +
                "7. Xiaomi/Oppo/Vivo: izinkan Autostart + hemat baterai \"No restrictions\" untuk aplikasi ini.\n\n" +
                "8. Aktifkan saklar \"Mulai otomatis\" agar GPS langsung dijaga setiap HP menyala.\n\n" +
                "9. Jangan \"Force Stop\" aplikasi — auto start tidak akan jalan sampai aplikasi dibuka lagi.\n\n" +
                "10. Network Keeper menyala otomatis bersama GPS: ping kecil tiap 4 detik menjaga koneksi data tidak idle.\n\n" +
                "Panduan warna ping: ● hijau < 30 ms (stabil), ● kuning 30–100 ms (sedang), ● merah > 100 ms (jelek). Loss > 20% = sinyal sering putus.\n\n" +
                "11. Peta butuh internet. Jika peta abu-abu kosong, periksa MAPS_API_KEY di Google Cloud Console."
            )
            .setPositiveButton("Mengerti", null)
            .show()
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
        statusText.text = if (running) "Status: 🟢 AKTIF • Mode Agresif" else "Status: ⚪ BERHENTI"
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
        val needBg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
        btnBackground.visibility = if (needBg) View.VISIBLE else View.GONE
    }
}
