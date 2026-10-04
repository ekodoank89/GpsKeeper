package com.aya.gps

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow

object LocationStore {
    val location = MutableStateFlow<Location?>(null)
    val running = MutableStateFlow(false)
    val satellites = MutableStateFlow("–/–")

    // Statistik jaringan (Network Keeper)
    val latencyMs = MutableStateFlow(-1)   // -1 = belum ada data
    val jitterMs = MutableStateFlow(-1f)
    val lossPct = MutableStateFlow(-1)
    val netMode = MutableStateFlow("")     // "ping" / "http"
    val netHost = MutableStateFlow("")     // server terpilih, mis. "1.1.1.1"
}
