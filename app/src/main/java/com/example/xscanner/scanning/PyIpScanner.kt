package com.example.xscanner.scanning

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class PyIpScanner(private val context: Context) {

    // Progress callback interface
    interface ProgressCallback {
        fun onProgress(scanned: Int, total: Long, valid: Int, currentIP: String?)
    }

    // Result callback interface
    interface ResultCallback {
        fun onResult(item: Map<String, Any>)
    }

    suspend fun scan(
        config: Map<String, String>,
        progressCallback: ProgressCallback,
        resultCallback: ResultCallback
    ) = withContext(Dispatchers.IO) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        val py = Python.getInstance()
        val module = py.getModule("scanner")

        // Provide the IP list file path (copy from raw resource to accessible location)
        val ipListPath = copyRawToTemp(context, R.raw.ipv4, "ipv4.txt")
        config["ip_list_path"] = ipListPath

        // Convert config to Python dict
        val pyConfig = py.builtins.dict()
        for ((key, value) in config) {
            pyConfig[key] = value
        }

        // Create Python callback wrappers
        val progressWrapper = object : PyObject.Callback {
            override fun call(args: Array<PyObject>?, kwargs: Map<String, PyObject>?): PyObject? {
                args?.let {
                    val scanned = it[0].toInt()
                    val total = it[1].toLong()
                    val valid = it[2].toInt()
                    val ip = if (it[3].isNone()) null else it[3].toString()
                    progressCallback.onProgress(scanned, total, valid, ip)
                }
                return null
            }
        }

        val resultWrapper = object : PyObject.Callback {
            override fun call(args: Array<PyObject>?, kwargs: Map<String, PyObject>?): PyObject? {
                args?.let {
                    val dict = it[0].asMap()
                    val map = mutableMapOf<String, Any>()
                    for (key in dict.keys()) {
                        map[key.toString()] = dict[key].toString()
                    }
                    resultCallback.onResult(map)
                }
                return null
            }
        }

        val pyProgress = py.javaProxy(progressWrapper)
        val pyResult = py.javaProxy(resultWrapper)

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