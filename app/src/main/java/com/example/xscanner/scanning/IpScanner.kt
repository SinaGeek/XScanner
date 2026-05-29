package com.example.xscanner.scanning

import android.content.Context
import com.example.xscanner.R
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class IpScanner(private val scanType: ScanType, private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun loadRanges(): List<String> {
        val resId = if (scanType == ScanType.IPV4) R.raw.ipv4 else R.raw.ipv6
        return context.resources.openRawResource(resId).bufferedReader().readLines()
            .filter { it.isNotBlank() }
    }

    suspend fun scan(
        config: ScanConfig,
        onProgress: suspend (tested: Int, total: Int) -> Unit,
        onResult: suspend (ResultItem) -> Unit
    ) = withContext(Dispatchers.IO) {
        val ranges = loadRanges()
        val allIps = ranges.toList()
        val total = allIps.size
        var tested = 0

        for (ipStr in allIps) {
            tested++
            if (tested % 10 == 0) {
                withContext(Dispatchers.Main) { onProgress(tested, total) }
            }

            // 1. TCP ping
            val pingMs = tcpPing(ipStr, 443, config.maxPing)
            if (pingMs < 0 || pingMs > config.maxPing) continue

            // 2. Latency & jitter
            val (latency, jitter) = measureLatencyJitter(ipStr, config.maxLatency)
            if (jitter > config.maxJitter || latency > config.maxLatency) continue

            // 3. Upload speed (must pass first to avoid unnecessary download test)
            val upload = testUploadSpeed(ipStr, config.testSizeKB, config.minUploadSpeedMbps)
            if (upload < config.minUploadSpeedMbps) continue

            // 4. Download speed
            val download = testDownloadSpeed(ipStr, config.testSizeKB, config.minDownloadSpeedMbps)
            if (download < config.minDownloadSpeedMbps) continue

            val item = ResultItem(ipStr, pingMs, 0.0, jitter, latency, upload, download)
            withContext(Dispatchers.Main) { onResult(item) }
        }
        withContext(Dispatchers.Main) { onProgress(tested, total) }
    }

    private fun tcpPing(ip: String, port: Int, timeoutMs: Int): Long {
        return try {
            val start = System.nanoTime()
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            val rtt = (System.nanoTime() - start) / 1_000_000
            socket.close()
            if (rtt > Int.MAX_VALUE) -1 else rtt
        } catch (e: Exception) {
            -1
        }
    }

    private suspend fun measureLatencyJitter(ip: String, maxLatencyMs: Int): Pair<Long, Long> =
        withContext(Dispatchers.IO) {
            val url = "https://$ip/__down?bytes=1000"
            val request = Request.Builder()
                .url(url)
                .header("Host", "speed.cloudflare.com")
                .build()
            val latencies = mutableListOf<Long>()
            for (i in 1..4) {
                try {
                    val start = System.nanoTime()
                    val response = client.newCall(request).execute()
                    response.body?.bytes()
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    if (elapsed <= maxLatencyMs) latencies.add(elapsed)
                } catch (_: Exception) {}
            }
            if (latencies.isEmpty()) return@withContext Pair(99999L, -1L)
            val avgLatency = latencies.average().toLong()
            val jitter = if (latencies.size > 1) {
                latencies.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toLong()
            } else 0L
            Pair(avgLatency, jitter)
        }

    private suspend fun testDownloadSpeed(ip: String, sizeKB: Int, minSpeedMbps: Double): Double =
        withContext(Dispatchers.IO) {
            val bytes = sizeKB * 1024
            val url = "https://$ip/__down?bytes=$bytes"
            val request = Request.Builder()
                .url(url)
                .header("Host", "speed.cloudflare.com")
                .build()
            try {
                val start = System.nanoTime()
                val response = client.newCall(request).execute()
                response.body?.bytes()
                val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
                (bytes * 8 / 1_000_000.0) / elapsed
            } catch (e: Exception) {
                0.0
            }
        }

    private suspend fun testUploadSpeed(ip: String, sizeKB: Int, minSpeedMbps: Double): Double =
        withContext(Dispatchers.IO) {
            val uploadBytes = sizeKB * 1024  // total upload size in bytes
            // Create a random payload (exact size)
            val payload = ByteArray(uploadBytes) { 'A'.code.toByte() }
            val mediaType = "application/octet-stream".toMediaType()
            val body = payload.toRequestBody(mediaType)

            val url = "https://$ip/__up"
            val request = Request.Builder()
                .url(url)
                .header("Host", "speed.cloudflare.com")
                .post(body)
                .build()

            try {
                val start = System.nanoTime()
                val response = client.newCall(request).execute()
                response.body?.bytes() // consume response
                val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
                if (elapsed > 0) (uploadBytes * 8 / 1_000_000.0) / elapsed else 0.0
            } catch (e: Exception) {
                0.0
            }
        }
}