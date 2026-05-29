package com.example.xscanner

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.xscanner.databinding.ActivityMainBinding
import com.example.xscanner.fragments.*
import com.example.xscanner.scanning.ScanType

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()

        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_ipv4 -> showFragment(ScanFragment.newInstance(ScanType.IPV4))
                R.id.nav_ipv6 -> showFragment(ScanFragment.newInstance(ScanType.IPV6))
                R.id.nav_defaults -> showFragment(SettingsFragment())
                R.id.nav_cloudflare -> showFragment(CloudflareFragment())
                R.id.nav_history -> showFragment(HistoryFragment())
                R.id.nav_about -> showFragment(AboutFragment())
                R.id.nav_exit -> finish()
            }
            binding.drawerLayout.close()
            true
        }

        if (savedInstanceState == null) {
            showFragment(ScanFragment.newInstance(ScanType.IPV4))
        }
    }

    fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    fun updateStatus(text: String) {
        binding.statusBar.text = text
    }
}