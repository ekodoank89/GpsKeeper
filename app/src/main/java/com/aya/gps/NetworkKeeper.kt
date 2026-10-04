package com.aya.gps

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

object NetworkKeeper {

    private const val PING_HOST = "8.8.8.8"
    private const val INTERVAL_MS = 4_000L
    private const val WINDOW = 10          // jumlah sampel utk jitter
    private const val LOSS_WINDOW = 20     // jumlah percobaan utk loss %

    private var job: Job? = null
    private val latencies = ArrayDeque<Double>()
    private val results = ArrayDeque<Boolean>()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        latencies.clear()
        results.clear()
        resetStats()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                probe()
                delay(INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        resetStats()
    }

    private fun resetStats() {
        LocationStore.latencyMs.value = -1
        LocationStore.jitterMs.value = -1f
        LocationStore.lossPct.value = -1
        LocationStore.netMode.value = ""
    }

    private fun probe() {
        var ms = pingOnce()
        if (ms != null) {
            LocationStore.netMode.value = "ping"
        } else {
            ms = httpOnce()
            if (ms != null) LocationStore.netMode.value = "http"
        }

        if (ms != null) {
            results.addLast(true)
            latencies.addLast(ms)
            while (latencies.size > WINDOW) latencies.removeFirst()

            LocationStore.latencyMs.value = ms.toInt()

            // Jitter = rata-rata selisih latensi antar sampel berurutan
            if (latencies.size >= 2) {
                val arr = latencies.toList()
                var sum = 0.0
                for (i in 1 until arr.size) sum += abs(arr[i] - arr[i - 1])
                LocationStore.jitterMs.value = (sum / (arr.size - 1)).toFloat()
            }
        } else {
            results.addLast(false)
        }

        while (results.size > LOSS_WINDOW) results.removeFirst()
        if (results.isNotEmpty()) {
            val lost = results.count { !it }
            LocationStore.lossPct.value = lost * 100 / results.size
        }
    }

    // Ping via binary sistem (tersedia di hampir semua HP Android)
    private fun pingOnce(): Double? = try {
        val proc = Runtime.getRuntime().exec(
            arrayOf("/system/bin/ping", "-c", "1", "-W", "2", PING_HOST)
        )
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        proc.destroy()
        Regex("time[=<]([0-9.]+)\\s*ms").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
    } catch (_: Exception) {
        null
    }

    // Cadangan: ukur waktu HTTP ke server pengecekan Google (respons tanpa isi)
    private fun httpOnce(): Double? = try {
        val start = System.nanoTime()
        val conn = URL("https://www.gstatic.com/generate_204").openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 2_500
        conn.readTimeout = 2_500
        conn.setRequestProperty("Connection", "close")
        val code = conn.responseCode
        conn.disconnect()
        if (code in 200..399) (System.nanoTime() - start) / 1_000_000.0 else null
    } catch (_: Exception) {
        null
    }
}
