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
import com.example.xscanner.scanning.*
import kotlinx.coroutines.*

class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private lateinit var scanner: IpScanner
    private val results = mutableListOf<ResultItem>()
    private lateinit var adapter: ResultAdapter
    private var scanJob: Job? = null
    private var isPaused = false
    private val scanType: ScanType by lazy {
        arguments?.getSerializable("type") as ScanType
    }

    companion object {
        fun newInstance(type: ScanType) = ScanFragment().apply {
            arguments = Bundle().apply { putSerializable("type", type) }
        }
    }

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

        // Get current config from MainActivity
        val config = (activity as MainActivity).scanConfig

        scanner = IpScanner(scanType, requireContext())

        // Button listeners
        binding.btnStart.setOnClickListener { startScan(config) }
        binding.btnPause.setOnClickListener { pauseScan() }
        binding.btnStop.setOnClickListener { stopScan() }
        binding.btnContinue.setOnClickListener { resumeScan(config) }

        // Automatically start scanning when fragment opens (as you wanted)
        startScan(config)
    }

    private fun startScan(config: ScanConfig) {
        scanJob?.cancel()
        results.clear()
        adapter.notifyDataSetChanged()
        binding.btnCopy.visibility = View.GONE
        enableButtons(started = true, paused = false)

        (activity as MainActivity).updateStatus("Scanning...")
        (activity as MainActivity).updateSettingsSummary(config)

        scanJob = lifecycleScope.launch {
            scanner.scan(
                config,
                onProgress = { tested, total ->
                    withContext(Dispatchers.Main) {
                        (activity as MainActivity).updateStatus("$tested/$total scanned")
                    }
                },
                onResult = { item ->
                    withContext(Dispatchers.Main) {
                        results.add(item)
                        adapter.notifyItemInserted(results.size - 1)
                        if (results.isNotEmpty()) binding.btnCopy.visibility = View.VISIBLE
                        (activity as MainActivity).updateStatus("Found ${results.size} valid IPs")
                    }
                }
            )
            withContext(Dispatchers.Main) {
                enableButtons(started = false, paused = false)
                (activity as MainActivity).updateStatus("Done – ${results.size} IPs")
            }
        }
    }

    private fun pauseScan() {
        scanJob?.cancel()   // cancel coroutine to pause
        isPaused = true
        enableButtons(started = true, paused = true)
        (activity as MainActivity).updateStatus("Paused – ${results.size} IPs")
    }

    private fun stopScan() {
        scanJob?.cancel()
        results.clear()
        adapter.notifyDataSetChanged()
        binding.btnCopy.visibility = View.GONE
        enableButtons(started = false, paused = false)
        (activity as MainActivity).updateStatus("Stopped")
    }

    private fun resumeScan(config: ScanConfig) {
        if (!isPaused) return
        isPaused = false
        // Resume by restarting (our scan picks up from current results list? Actually it restarts from scratch because tested IPs set lost)
        // Better to save tested IPs; for now we just restart scanning.
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