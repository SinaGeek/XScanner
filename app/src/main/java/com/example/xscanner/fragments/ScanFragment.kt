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
import com.example.xscanner.scanning.IpScanner
import com.example.xscanner.scanning.ResultItem
import com.example.xscanner.scanning.ScanConfig
import com.example.xscanner.scanning.ScanType
import kotlinx.coroutines.launch

class ScanFragment : Fragment() {
    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private lateinit var scanner: IpScanner
    private val results = mutableListOf<ResultItem>()
    private lateinit var adapter: ResultAdapter
    private var scanJob: kotlinx.coroutines.Job? = null
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

        scanner = IpScanner(scanType, requireContext())

        scanJob = lifecycleScope.launch {
            val config = ScanConfig() // default config (can be replaced with saved settings)
            (activity as? MainActivity)?.updateStatus("Scanning...")
            scanner.scan(
                config,
                onProgress = { tested, total ->
                    (activity as? MainActivity)?.updateStatus("$tested/$total scanned")
                },
                onResult = { item ->
                    results.add(item)
                    adapter.notifyItemInserted(results.size - 1)
                    if (results.isNotEmpty()) binding.btnCopy.visibility = View.VISIBLE
                    (activity as? MainActivity)?.updateStatus("Found ${results.size} valid IPs")
                }
            )
            (activity as? MainActivity)?.updateStatus("Done – ${results.size} IPs")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scanJob?.cancel()
        _binding = null
    }
}