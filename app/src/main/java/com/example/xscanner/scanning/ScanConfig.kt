package com.example.xscanner.scanning

data class ScanConfig(
    val maxIp: Int = 50,
    val maxPing: Int = 500,
    val maxJitter: Int = 100,
    val maxLatency: Int = 1000,
    val maxPacketLoss: Double = 0.5,
    val testSizeKB: Int = 1024,
    val minDownloadSpeedMbps: Double = 3.0,
    val minUploadSpeedMbps: Double = 0.2,
    val ipInclude: String = "",
    val ipExclude: String = ""
)