// File: app/src/main/java/com/example/xscanner/fragments/ScanFragment.kt
package com.example.xscanner.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.xscanner.MainActivity
import com.example.xscanner.adapters.ResultAdapter
import com.example.xscanner.databinding.FragmentScanBinding
import com.example.xscanner.scanning.PyIpScanner
import kotlinx.coroutines.*

class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private lateinit var scanner: PyIpScanner
    private val results = mutableListOf<Map<String, String>>()
    private lateinit var adapter: ResultAdapter
    private var scanJob: Job? = null
    private var isPaused = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ResultAdapter(results) { selectedIps ->
            val text = selectedIps.joinToString("\n")
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("IPs", text))
            Toast.makeText(requireContext(), "Copied ${selectedIps.size} IPs", Toast.LENGTH_SHORT).show()
        }
        binding.tableRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.tableRecycler.adapter = adapter
        binding.btnCopy.visibility = View.GONE

        val config = (activity as MainActivity).scanConfig
        scanner = PyIpScanner(requireContext())

        (activity as MainActivity).updateStatus("Press Start to scan")
        (activity as MainActivity).updateSettingsSummary(config)

        binding.btnStart.setOnClickListener { startScan(config) }
        binding.btnPause.setOnClickListener { pauseScan() }
        binding.btnStop.setOnClickListener { stopScan() }
        binding.btnContinue.setOnClickListener { resumeScan(config) }

        enableButtons(false, false)
    }

    private fun startScan(config: Map<String, String>) {
        scanJob?.cancel()
        results.clear()
        adapter.notifyDataSetChanged()
        binding.btnCopy.visibility = View.GONE
        enableButtons(true, false)

        (activity as MainActivity).updateStatus("Scanning...")
        (activity as MainActivity).updateSettingsSummary(config)

        scanJob = lifecycleScope.launch {
            scanner.scan(
                config,
                onProgress = { scanned, total, valid, currentIP ->
                    launch(Dispatchers.Main) {
                        val ipInfo = if (currentIP != null) " ($currentIP)" else ""
                        (activity as MainActivity).updateStatus("$scanned / $total scanned – $valid valid IPs$ipInfo")
                    }
                },
                onResult = { item ->
                    launch(Dispatchers.Main) {
                        results.add(item)
                        adapter.notifyItemInserted(results.size - 1)
                        if (results.isNotEmpty()) binding.btnCopy.visibility = View.VISIBLE
                    }
                }
            )
            withContext(Dispatchers.Main) {
                enableButtons(false, false)
                (activity as MainActivity).updateStatus("Done – ${results.size} IPs")
            }
        }
    }

    private fun pauseScan() {
        scanJob?.cancel()
        isPaused = true
        enableButtons(true, true)
        (activity as MainActivity).updateStatus("Paused – ${results.size} IPs")
    }

    private fun stopScan() {
        scanJob?.cancel()
        results.clear()
        adapter.notifyDataSetChanged()
        binding.btnCopy.visibility = View.GONE
        enableButtons(false, false)
        (activity as MainActivity).updateStatus("Stopped")
    }

    private fun resumeScan(config: Map<String, String>) {
        if (!isPaused) return
        isPaused = false
        startScan(config)
    }

    private fun enableButtons(started: Boolean, paused: Boolean) {
        binding.btnStart.isEnabled = !started
        binding.btnPause.isEnabled = started && !paused
        binding.btnStop.isEnabled = started
        binding.btnContinue.isEnabled = started && paused
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanJob?.cancel()
        _binding = null
    }
}