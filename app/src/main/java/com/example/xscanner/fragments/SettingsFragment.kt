package com.example.xscanner.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.xscanner.MainActivity
import com.example.xscanner.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val config = (activity as MainActivity).scanConfig

        binding.etMaxIp.setText(config["max_ip"] ?: "10")
        binding.etMaxPing.setText(config["max_ping"] ?: "500")
        binding.etMaxJitter.setText(config["max_jitter"] ?: "100")
        binding.etMaxLatency.setText(config["max_latency"] ?: "1000")
        binding.etMaxPacketLoss.setText(config["max_packet_loss"] ?: "0.5")
        binding.etTestSize.setText(config["test_size"] ?: "1024")
        binding.etMinDl.setText(config["min_download_speed"] ?: "3.0")
        binding.etMinUl.setText(config["min_upload_speed"] ?: "0.2")

        binding.btnSave.setOnClickListener {
            config["max_ip"] = binding.etMaxIp.text.toString()
            config["max_ping"] = binding.etMaxPing.text.toString()
            config["max_jitter"] = binding.etMaxJitter.text.toString()
            config["max_latency"] = binding.etMaxLatency.text.toString()
            config["max_packet_loss"] = binding.etMaxPacketLoss.text.toString()
            config["test_size"] = binding.etTestSize.text.toString()
            config["min_download_speed"] = binding.etMinDl.text.toString()
            config["min_upload_speed"] = binding.etMinUl.text.toString()
            (activity as MainActivity).updateSettingsSummary(config)
            Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}