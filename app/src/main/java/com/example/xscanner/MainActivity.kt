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

    var scanConfig = ScanConfig()

    // Fragment references to keep them alive
    private lateinit var ipv4Fragment: ScanFragment
    private lateinit var ipv6Fragment: ScanFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var cloudflareFragment: CloudflareFragment
    private lateinit var historyFragment: HistoryFragment
    private lateinit var aboutFragment: AboutFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        drawerToggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            R.string.drawer_open, R.string.drawer_close
        )
        binding.drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()

        // Create all fragments once
        ipv4Fragment = ScanFragment.newInstance(ScanType.IPV4)
        ipv6Fragment = ScanFragment.newInstance(ScanType.IPV6)
        settingsFragment = SettingsFragment()
        cloudflareFragment = CloudflareFragment()
        historyFragment = HistoryFragment()
        aboutFragment = AboutFragment()

        // Add all, hide all except first
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, ipv4Fragment, "IPv4")
            .add(R.id.fragment_container, ipv6Fragment, "IPv6")
            .add(R.id.fragment_container, settingsFragment, "Settings")
            .add(R.id.fragment_container, cloudflareFragment, "Cloudflare")
            .add(R.id.fragment_container, historyFragment, "History")
            .add(R.id.fragment_container, aboutFragment, "About")
            .hide(ipv6Fragment)
            .hide(settingsFragment)
            .hide(cloudflareFragment)
            .hide(historyFragment)
            .hide(aboutFragment)
            .commit()

        supportActionBar?.title = "IPv4 Scan"

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_ipv4 -> showFragment(ipv4Fragment, "IPv4 Scan")
                R.id.nav_ipv6 -> showFragment(ipv6Fragment, "IPv6 Scan")
                R.id.nav_defaults -> showFragment(settingsFragment, "Default Values")
                R.id.nav_cloudflare -> showFragment(cloudflareFragment, "Cloudflare API")
                R.id.nav_history -> showFragment(historyFragment, "Previous Scans")
                R.id.nav_about -> showFragment(aboutFragment, "About")
                R.id.nav_exit -> finish()
            }
            binding.drawerLayout.close()
            true
        }
    }

    private fun showFragment(fragment: Fragment, title: String) {
        supportActionBar?.title = title
        supportFragmentManager.beginTransaction()
            .hide(ipv4Fragment)
            .hide(ipv6Fragment)
            .hide(settingsFragment)
            .hide(cloudflareFragment)
            .hide(historyFragment)
            .hide(aboutFragment)
            .show(fragment)
            .commit()
    }

    fun updateStatus(status: String) {
        binding.tvScanStatus.text = status
    }

    fun updateSettingsSummary(config: ScanConfig) {
        binding.tvSettingsSummary.text =
            "Max Ping: ${config.maxPing}ms | Jitter: ${config.maxJitter}ms | Latency: ${config.maxLatency}ms"
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (drawerToggle.onOptionsItemSelected(item)) return true
        return super.onOptionsItemSelected(item)
    }
}