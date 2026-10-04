package com.aya.gps

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Hanya bereaksi pada broadcast resmi dari sistem saat selesai booting
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Hanya jalan jika pengguna mengaktifkan saklar auto start
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_AUTO_START, false)) return

        // Wajib punya izin lokasi
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine) return

        // Android 10+: memulai layanan lokasi dari latar butuh izin "All the time"
        val bgOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (!bgOk) return

        ContextCompat.startForegroundService(
            context, Intent(context, LocationService::class.java)
        )
    }

    companion object {
        const val PREFS = "aya_prefs"
        const val KEY_AUTO_START = "auto_start"
    }
}
