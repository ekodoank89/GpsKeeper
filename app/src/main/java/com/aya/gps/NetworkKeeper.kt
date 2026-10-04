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

    // Kandidat server — dipilih otomatis yang tercepat dari lokasi Anda
    private val CANDIDATES = listOf(
        "8.8.8.8",         // Google DNS
        "1.1.1.1",         // Cloudflare
        "9.9.9.9",         // Quad9
        "208.67.222.222"   // OpenDNS
    )

    private const val INTERVAL_MS = 3_000L       // ping tiap 3 detik (radio tetap segar)
    private const val RESELECT_MS = 5 * 60_000L  // evaluasi ulang host terbaik tiap 5 menit
    private const val WINDOW = 10                // jumlah sampel untuk jitter
    private const val LOSS_WINDOW = 20           // jumlah percobaan untuk loss %

    private var job: Job? = null
    private var bestHost = CANDIDATES[0]
    private var lastSelectMs = 0L
    private val latencies = ArrayDeque<Double>()
    private val results = ArrayDeque<Boolean>()

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        latencies.clear()
        results.clear()
        resetStats()
        job = scope.launch(Dispatchers.IO) {
            selectBestHost()
            while (isActive) {
                probe()
                delay(INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (now - lastSelectMs > RESELECT_MS) selectBestHost()
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
        LocationStore.netHost.value = ""
    }

    // Uji setiap kandidat 2x, pilih rata-rata tercepat
    private fun selectBestHost() {
        lastSelectMs = System.currentTimeMillis()
        var best = Double.MAX_VALUE
        var chosen: String? = null
        for (host in CANDIDATES) {
            val t1 = pingOnce(host) ?: continue
            val t2 = pingOnce(host) ?: continue
            val avg = (t1 + t2) / 2.0
            if (avg < best) {
                best = avg
                chosen = host
            }
        }
        if (chosen != null) {
            bestHost = chosen
            LocationStore.netHost.value = chosen
        }
    }

    private fun probe() {
        var ms = pingOnce(bestHost)
        if (ms != null) {
            LocationStore.netMode.value = "ping"
        } else {
            // Host terpilih bermasalah → coba pilih ulang, dan pakai HTTP sebagai cadangan
            ms = httpOnce()
            if (ms != null) LocationStore.netMode.value = "http"
        }

        if (ms != null) {
            results.addLast(true)
            latencies.addLast(ms)
            while (latencies.size > WINDOW) latencies.removeFirst()

            LocationStore.latencyMs.value = ms.toInt()

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

    private fun pingOnce(host: String): Double? = try {
        val proc = Runtime.getRuntime().exec(
            arrayOf("/system/bin/ping", "-c", "1", "-W", "2", host)
        )
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        proc.destroy()
        Regex("time[=<]([0-9.]+)\\s*ms").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
    } catch (_: Exception) {
        null
    }

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
