package com.example.xscanner.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.xscanner.databinding.FragmentCloudflareBinding

class CloudflareFragment : Fragment() {
    private var _binding: FragmentCloudflareBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCloudflareBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("cloudflare", Context.MODE_PRIVATE)
        // Load saved values (optional)
        binding.etEmail.setText(prefs.getString("email", ""))
        binding.etApiKey.setText(prefs.getString("api_key", ""))
        binding.etZoneId.setText(prefs.getString("zone_id", ""))
        binding.etSubdomain.setText(prefs.getString("subdomain", ""))

        binding.btnSaveCloudflare.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val apiKey = binding.etApiKey.text.toString().trim()
            val zoneId = binding.etZoneId.text.toString().trim()
            val subdomain = binding.etSubdomain.text.toString().trim()

            if (email.isEmpty() || apiKey.isEmpty() || zoneId.isEmpty() || subdomain.isEmpty()) {
                Toast.makeText(requireContext(), "Please fill all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("email", email)
                .putString("api_key", apiKey)
                .putString("zone_id", zoneId)
                .putString("subdomain", subdomain)
                .apply()

            Toast.makeText(requireContext(), "Cloudflare settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}