package com.example.xscanner.scanning

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PyIpScanner(private val context: Context) {

    // Java object that Python calls for progress
    class ProgressProxy {
        var callback: ((scanned: Int, total: Long, valid: Int, currentIP: String?) -> Unit)? = null

        // This method is called from Python: progress_callback.report(scanned, total, valid, ip)
        fun report(scanned: Int, total: Long, valid: Int, ip: String?) {
            callback?.invoke(scanned, total, valid, ip)
        }
    }

    // Java object that Python calls when a result is found
    class ResultProxy {
        var callback: ((item: Map<String, String>) -> Unit)? = null

        // Called from Python: result_callback.addResult( dict )
        fun addResult(item: Map<String, String>) {
            callback?.invoke(item)
        }
    }

    suspend fun scan(
        config: Map<String, String>,
        onProgress: (scanned: Int, total: Long, valid: Int, currentIP: String?) -> Unit,
        onResult: (item: Map<String, String>) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        val py = Python.getInstance()
        val module = py.getModule("scanner")

        // Copy ipv4.txt to a temp location so Python can read it
        val ipListPath = copyRawToTemp(context, com.example.xscanner.R.raw.ipv4, "ipv4.txt")
        val mutableConfig = config.toMutableMap()
        mutableConfig["ip_list_path"] = ipListPath

        // Build Python dict from the mutable config
        val pyConfig = py.builtins.dict()
        for ((key, value) in mutableConfig) {
            pyConfig[key] = value
        }

        // Create Java proxies for callbacks
        val progressProxy = ProgressProxy().also { it.callback = onProgress }
        val resultProxy = ResultProxy().also { it.callback = onResult }

        val pyProgress = com.chaquo.python.PyObject.fromJava(progressProxy)
        val pyResult = com.chaquo.python.PyObject.fromJava(resultProxy)

        // Run the Python function
        module.callAttr("run_scan", pyConfig, pyProgress, pyResult)
    }

    private fun copyRawToTemp(context: Context, resId: Int, fileName: String): String {
        val file = File(context.cacheDir, fileName)
        context.resources.openRawResource(resId).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }
}