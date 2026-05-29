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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

class IpScanner(private val scanType: ScanType, private val context: Context) {

    // Trust all certificates (like Python's ssl=False)
    private fun createUnsafeClient(): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val client = createUnsafeClient()

    // Parse CIDR ranges from raw resource
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
                        try {
                            val addr = InetAddress.getByName(line)
                            Pair(addr.address, 32)
                        } catch (e2: Exception) { null }
                    }
                } else {
                    try {
                        val addr = InetAddress.getByName(line)
                        Pair(addr.address, 32)
                    } catch (e: Exception) { null }
                }
            }
    }

    // Total hosts (for progress display)
    private fun totalHosts(ranges: List<Pair<ByteArray, Int>>): Long {
        var total = 0L
        for ((_, prefix) in ranges) {
            if (scanType == ScanType.IPV4) {
                if (prefix >= 32) total += 1L
                else {
                    val hosts = (1L shl (32 - prefix)) - 2L
                    total += hosts
                }
            } else return Long.MAX_VALUE
        }
        return total.coerceAtMost(Long.MAX_VALUE)
    }

    // Generate a random IP from the ranges (never returns null)
    private fun generateRandomIp(ranges: List<Pair<ByteArray, Int>>, rng: Random): String {
        if (ranges.isEmpty()) return "1.1.1.1"
        for (attempt in 1..1000) {
            val (base, prefix) = ranges.random(rng)
            if (scanType == ScanType.IPV4) {
                val baseInt = ((base[0].toInt() and 0xFF) shl 24) or
                        ((base[1].toInt() and 0xFF) shl 16) or
                        ((base[2].toInt() and 0xFF) shl 8) or
                        (base[3].toInt() and 0xFF)
                if (prefix >= 32) {
                    val oct1 = (baseInt ushr 24) and 0xFF
                    val oct2 = (baseInt ushr 16) and 0xFF
                    val oct3 = (baseInt ushr 8) and 0xFF
                    val oct4 = baseInt and 0xFF
                    return "$oct1.$oct2.$oct3.$oct4"
                } else {
                    val networkMask = (-1) shl (32 - prefix)
                    val network = baseInt and networkMask
                    val hosts = (1L shl (32 - prefix)) - 2L
                    if (hosts <= 0) continue
                    val offset = rng.nextLong(1, hosts + 1)
                    val ipInt = network + offset.toInt()
                    val oct1 = (ipInt ushr 24) and 0xFF
                    val oct2 = (ipInt ushr 16) and 0xFF
                    val oct3 = (ipInt ushr 8) and 0xFF
                    val oct4 = ipInt and 0xFF
                    return "$oct1.$oct2.$oct3.$oct4"
                }
            }
        }
        return "1.1.1.1"  // fallback
    }

    // TCP ping with multiple attempts -> avg ping and packet loss
    private fun tcpPingWithLoss(ip: String, port: Int, timeoutMs: Int, count: Int = 5): Pair<Long, Double> {
        var successful = 0
        var totalTime = 0L
        for (i in 1..count) {
            val rtt = tcpPing(ip, port, timeoutMs)
            if (rtt > 0) {
                successful++
                totalTime += rtt
            }
        }
        if (successful == 0) return Pair(-1L, 1.0)
        val avgPing = totalTime / successful
        val loss = 1.0 - (successful.toDouble() / count)
        return Pair(avgPing, loss)
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

    // Latency & jitter using 4 small downloads
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

    // Speed tests
    private suspend fun testDownloadSpeed(ip: String, sizeKB: Int): Double =
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
            } catch (e: Exception) { 0.0 }
        }

    private suspend fun testUploadSpeed(ip: String, sizeKB: Int): Double =
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
            } catch (e: Exception) { 0.0 }
        }

    // ---------- Main scan ----------
    suspend fun scan(
        config: ScanConfig,
        onProgress: suspend (scanned: Int, total: Long, validCount: Int, currentIP: String?) -> Unit,
        onResult: suspend (ResultItem) -> Unit
    ) = withContext(Dispatchers.IO) {
        val ranges = loadCidrRanges()
        val total = totalHosts(ranges)
        var scanned = 0
        var validFound = 0
        val testedIps = mutableSetOf<String>()
        val rng = Random(System.nanoTime())

        // Initial progress with total
        withContext(Dispatchers.Main) { onProgress(0, total, 0, null) }

        while (validFound < config.maxIp) {
            val ip = generateRandomIp(ranges, rng)
            if (ip in testedIps) continue
            testedIps.add(ip)
            scanned++

            // Update status with current IP
            withContext(Dispatchers.Main) { onProgress(scanned, total, validFound, ip) }

            // Packet loss and average ping (5 tries)
            val (avgPing, loss) = tcpPingWithLoss(ip, 443, config.maxPing)
            if (avgPing < 0 || avgPing > config.maxPing || loss > config.maxPacketLoss) continue

            // Latency & jitter
            val (latency, jitter) = measureLatencyJitter(ip, config.maxLatency)
            if (jitter > config.maxJitter || latency > config.maxLatency) continue

            // Upload speed
            val upload = testUploadSpeed(ip, config.testSizeKB)
            if (upload < config.minUploadSpeedMbps) continue

            // Download speed
            val download = testDownloadSpeed(ip, config.testSizeKB)
            if (download < config.minDownloadSpeedMbps) continue

            // Valid IP found
            val item = ResultItem(ip, avgPing, loss, jitter, latency, upload, download)
            withContext(Dispatchers.Main) { onResult(item) }
            validFound++
            withContext(Dispatchers.Main) { onProgress(scanned, total, validFound, null) }
        }
        withContext(Dispatchers.Main) { onProgress(scanned, total, validFound, null) }
    }
}