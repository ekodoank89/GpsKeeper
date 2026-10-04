package com.aya.gps

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow

object LocationStore {
    val location = MutableStateFlow<Location?>(null)
    val running = MutableStateFlow(false)
    val satellites = MutableStateFlow("–/–")
}
