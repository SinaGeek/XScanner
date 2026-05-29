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

    // Load CIDR ranges from raw resource, parse them into pairs (base address, prefix length)
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

    // Calculate total possible hosts from a list of CIDR ranges (approximate, for progress)
    private fun totalHosts(ranges: List<Pair<ByteArray, Int>>): Long {
        var total = 0L
        for ((_, prefix) in ranges) {
            val hosts = if (scanType == ScanType.IPV4) {
                if (prefix >= 32) 0L else (1L shl (32 - prefix)) - 2  // exclude network & broadcast
            } else {
                // IPv6: extremely large, just return a huge number to avoid overflow
                Long.MAX_VALUE
            }
            total += hosts
            if (total < 0) return Long.MAX_VALUE // overflow guard
        }
        return total
    }

    // Random IP generator that yields random IPs from the CIDR ranges
    private fun randomIpGenerator(ranges: List<Pair<ByteArray, Int>>): suspend () -> String? {
        val rng = Random(System.nanoTime())
        return {
            if (ranges.isEmpty()) null
            val (base, prefix) = ranges.random(rng)
            if (scanType == ScanType.IPV4) {
                val baseInt = ((base[0].toInt() and 0xFF) shl 24) or
                        ((base[1].toInt() and 0xFF) shl 16) or
                        ((base[2].toInt() and 0xFF) shl 8) or
                        (base[3].toInt() and 0xFF)
                val mask = if (prefix == 0) 0 else (0xFFFFFFFF.toInt() ushr (32 - prefix))
                val network = baseInt and mask
                val hosts = if (prefix >= 32) 0 else (1 shl (32 - prefix)) - 2
                if (hosts <= 0) null
                val offset = rng.nextInt(1, hosts + 1)
                val ipInt = network + offset
                val octet1 = (ipInt ushr 24) and 0xFF
                val octet2 = (ipInt ushr 16) and 0xFF
                val octet3 = (ipInt ushr 8) and 0xFF
                val octet4 = ipInt and 0xFF
                "$octet1.$octet2.$octet3.$octet4"
            } else {
                // IPv6: skip for now (rarely used for CF scanning)
                null
            }
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
        val testedIps = mutableSetOf<String>()  // to avoid duplicates
        val gen = randomIpGenerator(ranges)

        while (validFound < config.maxIp) {
            val ip = withContext(Dispatchers.Default) { gen() } ?: break
            if (ip in testedIps) continue
            testedIps.add(ip)
            scanned++

            // TCP ping
            val pingMs = tcpPing(ip, 443, config.maxPing)
            if (pingMs < 0 || pingMs > config.maxPing) continue

            // Latency & jitter
            val (latency, jitter) = measureLatencyJitter(ip, config.maxLatency)
            if (jitter > config.maxJitter || latency > config.maxLatency) continue

            // Upload speed (must pass first)
            val upload = testUploadSpeed(ip, config.testSizeKB, config.minUploadSpeedMbps)
            if (upload < config.minUploadSpeedMbps) continue

            // Download speed
            val download = testDownloadSpeed(ip, config.testSizeKB, config.minDownloadSpeedMbps)
            if (download < config.minDownloadSpeedMbps) continue

            val item = ResultItem(ip, pingMs, 0.0, jitter, latency, upload, download)
            withContext(Dispatchers.Main) { onResult(item) }
            validFound++

            // Report progress every 10 scans or when valid found
            if (scanned % 10 == 0 || validFound % 5 == 0) {
                withContext(Dispatchers.Main) { onProgress(scanned, total, validFound) }
            }
        }
        withContext(Dispatchers.Main) { onProgress(scanned, total, validFound) }
    }

    // ... (tcpPing, measureLatencyJitter, testDownloadSpeed, testUploadSpeed remain the same as before)
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