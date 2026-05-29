package com.example.xscanner

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.xscanner.databinding.ActivityMainBinding
import com.example.xscanner.fragments.*
import com.example.xscanner.scanning.ScanConfig
import com.example.xscanner.scanning.ScanType

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var drawerToggle: ActionBarDrawerToggle

    // Current scan config (can be updated from SettingsFragment)
    var scanConfig = ScanConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar as action bar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Drawer toggle
        drawerToggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.app_name, R.string.app_name
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        // Navigation item selection
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_ipv4 -> loadFragment(ScanFragment.newInstance(ScanType.IPV4), "IPv4 Scan")
                R.id.nav_ipv6 -> loadFragment(ScanFragment.newInstance(ScanType.IPV6), "IPv6 Scan")
                R.id.nav_defaults -> loadFragment(SettingsFragment(), "Default Values")
                R.id.nav_cloudflare -> loadFragment(CloudflareFragment(), "Cloudflare API")
                R.id.nav_history -> loadFragment(HistoryFragment(), "Previous Scans")
                R.id.nav_about -> loadFragment(AboutFragment(), "About")
                R.id.nav_exit -> finish()
            }
            binding.drawerLayout.close()
            true
        }

        // Start with IPv4 scan
        if (savedInstanceState == null) {
            loadFragment(ScanFragment.newInstance(ScanType.IPV4), "IPv4 Scan")
        }
    }

    fun loadFragment(fragment: Fragment, title: String) {
        supportActionBar?.title = title
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    fun updateStatus(status: String) {
        binding.tvScanStatus.text = status
    }

    fun updateSettingsSummary(config: ScanConfig) {
        binding.tvSettingsSummary.text = "Max Ping: ${config.maxPing}ms | Jitter: ${config.maxJitter}ms | Latency: ${config.maxLatency}ms"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) return true
        return super.onOptionsItemSelected(item)
    }
}