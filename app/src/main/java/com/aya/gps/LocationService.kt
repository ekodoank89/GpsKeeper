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
import java.util.Locale

class LocationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fusedClient: FusedLocationProviderClient? = null
    private var locationManager: LocationManager? = null
    private var fusedCallback: LocationCallback? = null
    private var gnssCallback: GnssStatus.Callback? = null

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
        val notification = buildNotification("Mode Agresif: memindai semua sumber lokasi…")
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        LocationStore.running.value = true
        startAllProviders()
        NetworkKeeper.start(serviceScope)   // ← keep-alive jaringan, otomatis ikut menyala
        return START_STICKY
    }

    override fun onDestroy() {
        NetworkKeeper.stop()
        serviceScope.cancel()
        fusedCallback?.let { cb -> try { fusedClient?.removeLocationUpdates(cb) } catch (_: Exception) {} }
        try { locationManager?.removeUpdates(rawListener) } catch (_: Exception) {}
        gnssCallback?.let { cb -> try { locationManager?.unregisterGnssStatusCallback(cb) } catch (_: Exception) {} }
        LocationStore.running.value = false
        super.onDestroy()
    }

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
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .setMaxUpdateDelayMillis(2_000L)
            .setWaitForAccurateLocation(false)
            .build()
        fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { onNewLocation(it) }
            }
        }
        try {
            fusedClient?.requestLocationUpdates(request, fusedCallback!!, Looper.getMainLooper())
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

    private fun updateNotification(loc: Location) {
        val net = LocationStore.latencyMs.value
        val netInfo = if (net >= 0)
            String.format(Locale.US, " | jaringan %d ms (%s)", net, LocationStore.netMode.value)
        else ""
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_ID, buildNotification(
                String.format(
                    Locale.US, "%.5f, %.5f | ±%.0f m | %s | satelit %s%s",
                    loc.latitude, loc.longitude, loc.accuracy,
                    loc.provider, LocationStore.satellites.value, netInfo
                )
            )
        )
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📡 AYA GPS — Mode Agresif")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
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
    }
}
