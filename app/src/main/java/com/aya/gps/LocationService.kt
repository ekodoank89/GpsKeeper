package com.aya.gps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class LocationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationManager: LocationManager? = null
    private var fusedCallback: LocationCallback? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastResubscribeMs = 0L

    private val rawListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onNewLocation(location)
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            buildNotification("Mode Agresif: memindai semua sumber lokasi…"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        acquireWakeLock()
        LocationStore.running.value = true
        startAllProviders()
        startWatchdog()
        NetworkKeeper.start(serviceScope)
        return START_STICKY
    }

    override fun onDestroy() {
        NetworkKeeper.stop()
        serviceScope.cancel()
        fusedCallback?.let { cb -> try { fusedClient?.removeLocationUpdates(cb) } catch (_: Exception) {} }
        try { locationManager?.removeUpdates(rawListener) } catch (_: Exception) {}
        gnssCallback?.let { cb -> try { locationManager?.unregisterGnssStatusCallback(cb) } catch (_: Exception) {} }
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        LocationStore.running.value = false
        super.onDestroy()
    }

    // ---------- WAKE LOCK: CPU tetap aktif agar fused terus mengirim walau layar mati ----------
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AYAGPS::location").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // pengaman 24 jam; dilepas otomatis di onDestroy
        }
    }

    // ---------- WATCHDOG: jika tidak ada update lokasi > 15 detik, sambung ulang otomatis ----------
    private fun startWatchdog() {
        serviceScope.launch {
            while (true) {
                delay(5_000)
                val loc = LocationStore.location.value
                val ageMs = loc?.let {
                    (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000
                } ?: Long.MAX_VALUE
                if (ageMs > 15_000) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastResubscribeMs > 10_000) {
                        lastResubscribeMs = now
                        resubscribeFused()
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(
                            NOTIFICATION_ID,
                            buildNotification("Sinyal terputus — menghubungkan ulang GPS…")
                        )
                    }
                }
            }
        }
    }

    private fun resubscribeFused() {
        val client = fusedClient ?: return
        val cb = fusedCallback ?: return
        try { client.removeLocationUpdates(cb) } catch (_: Exception) {}
        try {
            client.requestLocationUpdates(aggressiveRequest(), cb, Looper.getMainLooper())
        } catch (_: Exception) {}
        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                ?.addOnSuccessListener { l -> l?.let { onNewLocation(it) } }
        } catch (_: Exception) {}
    }

    private fun aggressiveRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(2_000L)
            .setWaitForAccurateLocation(false)
            .build()

    private fun startAllProviders() {
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { stopSelf(); return }

        // ---- SUMBER 1: Fused (GPS + Wi-Fi + seluler) tiap 1 detik ----
        fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onNewLocation(it) }
            }
        }
        try {
            fusedClient?.requestLocationUpdates(
                aggressiveRequest(), fusedCallback!!, Looper.getMainLooper()
            )
        } catch (_: SecurityException) {}

        // ---- Warm-up: minta fix terbaik SEGERA saat mulai ----
        try {
            fusedClient?.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token
            )?.addOnSuccessListener { loc -> loc?.let { onNewLocation(it) } }
        } catch (_: Exception) {}

        // ---- SUMBER 2: GPS satelit mentah ----
        if (fine) subscribe(LocationManager.GPS_PROVIDER, 1_000L)

        // ---- SUMBER 3: Jaringan seluler/Wi-Fi (kunci di dalam gedung) ----
        subscribe(LocationManager.NETWORK_PROVIDER, 1_000L)

        // ---- SUMBER 4: Passive ----
        if (fine) subscribe(LocationManager.PASSIVE_PROVIDER, 0L)

        // ---- Monitor satelit GNSS ----
        if (fine) registerGnssMonitor()
    }

    private fun subscribe(provider: String, minTimeMs: Long) {
        try {
            val lm = locationManager ?: return
            if (lm.allProviders.contains(provider)) {
                lm.requestLocationUpdates(provider, minTimeMs, 0f, rawListener, Looper.getMainLooper())
            }
        } catch (_: Exception) {}
    }

    private fun registerGnssMonitor() {
        gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var total = 0
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    total++
                    if (status.usedInFix(i)) used++
                }
                LocationStore.satellites.value = "$used/$total"
                LocationStore.location.value?.let { updateNotification(it) }
            }
        }
        try {
            val lm = locationManager ?: return
            if (Build.VERSION.SDK_INT >= 28) {
                lm.registerGnssStatusCallback(gnssCallback!!, Handler(Looper.getMainLooper()))
            } else {
                @Suppress("DEPRECATION")
                lm.registerGnssStatusCallback(gnssCallback!!)
            }
        } catch (_: Exception) {}
    }

    private fun onNewLocation(loc: Location) {
        val current = LocationStore.location.value
        val newer = current == null || loc.time >= current.time
        val muchBetter = current != null && loc.accuracy < current.accuracy * 0.8f
        if (newer || muchBetter) {
            LocationStore.location.value = loc
            updateNotification(loc)
        }
    }

    // ---------- Notifikasi: ping + jitter + loss, semua berwarna ----------
    private fun updateNotification(loc: Location) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildLocationNotification(loc))
    }

    // Warna ping : <30 hijau | 30-100 kuning | >100 merah
    private fun statusColor(latMs: Int): Int = when {
        latMs < 30 -> COLOR_GREEN
        latMs <= 100 -> COLOR_YELLOW
        else -> COLOR_RED
    }

    // Warna jitter : <30 hijau | 30-100 kuning | >100 merah
    private fun jitterColor(j: Float): Int = when {
        j < 30f -> COLOR_GREEN
        j <= 100f -> COLOR_YELLOW
        else -> COLOR_RED
    }

    // Warna loss : 0% hijau | 1-20% kuning | >20% merah
    private fun lossColor(l: Int): Int = when {
        l <= 0 -> COLOR_GREEN
        l <= 20 -> COLOR_YELLOW
        else -> COLOR_RED
    }

    private fun SpannableStringBuilder.appendColored(text: String, color: Int) {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun buildLocationNotification(loc: Location): Notification {
        val base = String.format(
            Locale.US, "%.5f, %.5f | ±%.0f m | %s | satelit %s",
            loc.latitude, loc.longitude, loc.accuracy,
            loc.provider, LocationStore.satellites.value
        )
        val sb = SpannableStringBuilder(base)

        val lat = LocationStore.latencyMs.value
        if (lat >= 0) {
            sb.append("  |  ")
            sb.appendColored("● ${lat} ms", statusColor(lat))
            sb.append(" (${LocationStore.netMode.value})")

            val jit = LocationStore.jitterMs.value
            val loss = LocationStore.lossPct.value

            sb.append("  |  ")
            if (jit >= 0) {
                sb.appendColored(
                    String.format(Locale.US, "jit %.1f ms", jit), jitterColor(jit)
                )
            } else {
                sb.append("jit …")   // belum cukup sampel
            }

            sb.append("  ")
            if (loss >= 0) {
                sb.appendColored("loss ${loss}%", lossColor(loss))
            } else {
                sb.append("loss …")
            }
        }
        return buildNotification(sb)
    }

    private fun buildNotification(text: CharSequence): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📡 AYA GPS — Mode Agresif")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setColor(0xFF0D47A1.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "AYA GPS", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Menjaga GPS & jaringan tetap aktif" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "aya_gps"
        const val NOTIFICATION_ID = 1

        val COLOR_GREEN = 0xFF2E7D32.toInt()
        val COLOR_YELLOW = 0xFFF9A825.toInt()
        val COLOR_RED = 0xFFC62828.toInt()
    }
}
