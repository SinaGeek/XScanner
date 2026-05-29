class ScanFragment : Fragment(), ResultAdapter.OnSortListener {
    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!!
    private lateinit var scanner: IpScanner
    private lateinit var adapter: ResultAdapter
    private val results = mutableListOf<ResultItem>()
    private var scanJob: Job? = null
    private val scanType by lazy { arguments?.getSerializable("type") as ScanType }

    companion object {
        fun newInstance(type: ScanType) = ScanFragment().apply {
            arguments = Bundle().apply { putSerializable("type", type) }
        }
    }

    override fun onCreateView(...): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = ResultAdapter(results, this)
        binding.tableRecycler.adapter = adapter
        scanner = IpScanner(scanType, requireContext())
        binding.btnCopy.visibility = View.GONE

        // Start scan
        scanJob = lifecycleScope.launch {
            scanner.scan(
                onNewResult = { item ->
                    results.add(item)
                    adapter.notifyItemInserted(results.size - 1)
                    if (results.isNotEmpty()) binding.btnCopy.visibility = View.VISIBLE
                    (activity as? MainActivity)?.updateStatus("Found ${results.size} valid IPs")
                },
                onScanProgress = { tested, total ->
                    (activity as? MainActivity)?.updateStatus("Scanning $tested/$total")
                },
                config = getConfigFromSettings()
            )
        }

        // Copy selected IPs
        binding.btnCopy.setOnClickListener {
            val selected = adapter.getSelectedItems()
            val text = selected.joinToString("\n") { it.ip }
            val clipboard = requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("IPs", text))
            Toast.makeText(requireContext(), "Copied ${selected.size} IPs", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSort(column: Int, ascending: Boolean) {
        // Sort results and refresh adapter
    }
    // ... other lifecycle ...
}