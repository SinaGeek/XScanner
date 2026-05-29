package com.example.xscanner.scanning

data class ResultItem(
    val ip: String,
    val ping: Long,        // ms
    val packetLoss: Double, // 0.0 - 1.0
    val jitter: Long,       // ms
    val latency: Long,      // ms
    val uploadMbps: Double,
    val downloadMbps: Double
)