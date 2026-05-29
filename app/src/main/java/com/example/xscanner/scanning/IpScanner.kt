package com.example.xscanner.scanning

import android.content.Context
import com.example.xscanner.R
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class IpScanner(private val scanType: ScanType, private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // Load CIDR ranges from raw resource, parse into (base IP bytes, prefix length)
    private fun loadCidrRanges(): List<Pair<ByteArray, Int>> {
        val resId = if (scanType == ScanType.IPV4) R.raw.ipv4 else R.raw.ipv6
        return context.resources.openRawResource(resId).bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("/")
                if (parts.size == 2) {
                    try {
                        val addr = InetAddress.getByName(parts[0])
                        val prefix = parts[1].toInt()
                        Pair(addr.address, prefix)
                    } catch (e: Exception) {
                        null
                    }
                } else null
            }
    }

    // Calculate total possible hosts from the CIDR ranges (used only for progress display)
    private fun totalHosts(ranges: List<Pair<ByteArray, Int>>): Long {
        var total = 0L
        for ((_, prefix) in ranges) {
            if (scanType == ScanType.IPV4) {
                if (prefix >= 32) continue
                val hosts = (1L shl (32 - prefix)) - 2L
                total += hosts
            } else {
                return Long.MAX_VALUE  // IPv6 huge, avoid overflow
            }
        }
        return total.coerceAtMost(Long.MAX_VALUE)
    }

    // Returns a suspend lambda that generates a random IP string from the given ranges,
    // or null if no IP can be generated.
    private fun randomIpGenerator(ranges: List<Pair<ByteArray, Int>>): suspend () -> String? {
        if (ranges.isEmpty()) return { null }
        val rng = Random(System.nanoTime())
        return {
            if (scanType == ScanType.IPV4) {
                val (base, prefix) = ranges.random(rng)
                val baseInt = ((base[0].toInt() and 0xFF) shl 24) or
                        ((base[1].toInt() and 0xFF) shl 16) or
                        ((base[2].toInt() and 0xFF) shl 8) or
                        (base[3].toInt() and 0xFF)
                val networkMask = (-1) shl (32 - prefix)
                val network = baseInt and networkMask
                val hosts = if (prefix >= 32) 0L else (1L shl (32 - prefix)) - 2L
                if (hosts <= 0) return@return null
                val offset = rng.nextLong(1, hosts + 1)
                val ipInt = network + offset.toInt()
                val oct1 = (ipInt ushr 24) and 0xFF
                val oct2 = (ipInt ushr 16) and 0xFF
                val oct3 = (ipInt ushr 8) and 0xFF
                val oct4 = ipInt and 0xFF
                "$oct1.$oct2.$oct3.$oct4"
            } else null  // IPv6 not yet supported
        }
    }

    suspend fun scan(
        config: ScanConfig,
        onProgress: suspend (scanned: Int, total: Long, validCount: Int) -> Unit,
        onResult: suspend (ResultItem) -> Unit
    ) = withContext(Dispatchers.IO) {
        val ranges = loadCidrRanges()
        val total = totalHosts(ranges)
        var scanned = 0
        var validFound = 0
        val testedIps = mutableSetOf<String>()
        val gen = randomIpGenerator(ranges)

        // Show initial total
        withContext(Dispatchers.Main) { onProgress(0, total, 0) }

        while (validFound < config.maxIp) {
            val ip = gen() ?: break
            if (ip in testedIps) continue
            testedIps.add(ip)
            scanned++

            // TCP ping
            val pingMs = tcpPing(ip, 443, config.maxPing)
            if (pingMs < 0 || pingMs > config.maxPing) {
                if (scanned % 10 == 0) withContext(Dispatchers.Main) { onProgress(scanned, total, validFound) }
                continue
            }

            // Latency & jitter
            val (latency, jitter) = measureLatencyJitter(ip, config.maxLatency)
            if (jitter > config.maxJitter || latency > config.maxLatency) {
                if (scanned % 10 == 0) withContext(Dispatchers.Main) { onProgress(scanned, total, validFound) }
                continue
            }

            // Upload speed
            val upload = testUploadSpeed(ip, config.testSizeKB, config.minUploadSpeedMbps)
            if (upload < config.minUploadSpeedMbps) {
                if (scanned % 10 == 0) withContext(Dispatchers.Main) { onProgress(scanned, total, validFound) }
                continue
            }

            // Download speed
            val download = testDownloadSpeed(ip, config.testSizeKB, config.minDownloadSpeedMbps)
            if (download < config.minDownloadSpeedMbps) {
                if (scanned % 10 == 0) withContext(Dispatchers.Main) { onProgress(scanned, total, validFound) }
                continue
            }

            val item = ResultItem(ip, pingMs, 0.0, jitter, latency, upload, download)
            withContext(Dispatchers.Main) { onResult(item) }
            validFound++

            // Update progress after each valid IP
            withContext(Dispatchers.Main) { onProgress(scanned, total, validFound) }
        }
        withContext(Dispatchers.Main) { onProgress(scanned, total, validFound) }
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
            val uploadBytes = sizeKB * 1024
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
                response.body?.bytes()
                val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
                if (elapsed > 0) (uploadBytes * 8 / 1_000_000.0) / elapsed else 0.0
            } catch (e: Exception) {
                0.0
            }
        }
}