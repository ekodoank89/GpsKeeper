package com.example.gpskeeper

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow

object LocationStore {
    val location = MutableStateFlow<Location?>(null)
    val running = MutableStateFlow(false)
}
