package com.rakibulcodes.callerinfo

import android.app.role.RoleManager
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.app.NotificationManager
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import android.content.res.ColorStateList
import android.os.PowerManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import androidx.recyclerview.widget.LinearLayoutManager
import com.rakibulcodes.callerinfo.data.CallerInfoRepository
import com.rakibulcodes.callerinfo.data.TelegramManager
import com.rakibulcodes.callerinfo.data.TelegramCredentials
import com.rakibulcodes.callerinfo.data.database.CallerInfoEntity
import com.rakibulcodes.callerinfo.databinding.ActivityMainBinding
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: CallerInfoRepository
    private lateinit var telegramManager: TelegramManager
    private lateinit var callerDiagnostics: CallerDiagnosticTrail
    private lateinit var historyAdapter: HistoryAdapter
    private lateinit var gestureDetector: GestureDetectorCompat
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var latestLookupResult: CallerInfoEntity? = null
    private var updatingRecentCallSwitch = false
    private var updatingCallerIdSwitch = false
    private var enableAfterRoleRequest = false
    private val manualLookupGeneration = com.rakibulcodes.callerinfo.data.RequestGenerationTracker()
    private var manualLookupJob: Job? = null
    private var activeManualLookupGeneration: Long? = null
    private var initialMainContentPadding: Rect? = null
    private var pendingLookupCountForStatus: Int = 0
    private var allHistoryItems: List<CallerInfoEntity> = emptyList()
    private var historyFilter = HistoryFilter()
    private val bottomClearanceUpdate = Runnable { updateBottomContentClearance() }
    private val bottomClearanceLayoutListener = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        scheduleBottomContentClearanceUpdate()
    }

    private val roleRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val roleHeld = isCallerScreeningRoleHeld()
        if (enableAfterRoleRequest) {
            setCallerIdEnabled(roleHeld)
            Toast.makeText(
                this,
                if (roleHeld) "Call Screening role granted" else "Call Screening role not granted. Caller ID remains off.",
                if (roleHeld) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
        enableAfterRoleRequest = false
        refreshAppStatus()
    }

    private val exportCallerDataLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null && ::repository.isInitialized) {
                lifecycleScope.launch {
                    val json = repository.exportCallerData()
                    runCatching {
                        contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
                            ?: error("Unable to open export destination")
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "Export failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private val importCallerDataLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && ::repository.isInitialized) {
                lifecycleScope.launch {
                    val text = runCatching {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                    if (text == null) {
                        Toast.makeText(this@MainActivity, R.string.import_invalid, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val result = repository.importCallerData(text)
                    if (result.invalidFile) {
                        Toast.makeText(this@MainActivity, R.string.import_invalid, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.import_complete, result.imported, result.skipped),
                            Toast.LENGTH_LONG
                        ).show()
                        loadHistory()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)

        super.onCreate(savedInstanceState)

        gestureDetector = GestureDetectorCompat(this, SwipeGestureListener())

        repository = CallerInfoRepository.getInstance(applicationContext)
        telegramManager = TelegramManager.getInstance(applicationContext)
        callerDiagnostics = CallerDiagnostics.getInstance(applicationContext)
        initializeUI()
        if (!telegramManager.isNativeAvailable()) {
            setLoginStatus(getString(R.string.native_integration_unavailable), isError = true)
        }
        observeTelegramState()

        setupNetworkListener()
    }

    private fun setupNetworkListener() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                OfflineLookupScheduler.enqueue(applicationContext)
                if (telegramManager.isReady()) {
                    telegramManager.reconnect()
                }
                runOnUiThread { refreshPendingLookupStatus() }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                runOnUiThread { refreshPendingLookupStatus() }
            }
        }
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    override fun onDestroy() {
        cancelManualLookup()
        if (::binding.isInitialized) {
            binding.mainRoot.removeCallbacks(bottomClearanceUpdate)
            binding.mainRoot.removeOnLayoutChangeListener(bottomClearanceLayoutListener)
            binding.bottomNavigationContainer.removeOnLayoutChangeListener(bottomClearanceLayoutListener)
            ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot, null)
            binding.etNumberFormattingPreview.text?.clear()
            binding.tvNumberFormattingPreviewResult.text = ""
        }
        CallerOverlayService.dismissPreview(applicationContext)
        super.onDestroy()
        networkCallback?.let {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
    }

    private fun initializeUI() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBottomContentClearance()

        setSupportActionBar(binding.toolbar)

        setupBottomNavigation()
        setupHistory()
        setupSetupSection()

        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val savedNavId = prefs.getInt("active_nav", R.id.nav_lookup)
        val initialNavId = when (savedNavId) {
            R.id.nav_lookup, R.id.nav_history, R.id.nav_settings, R.id.nav_info -> savedNavId
            else -> R.id.nav_lookup
        }

        // On a fresh launch, BottomNavigation may not dispatch selection callback for its default item.
        // Apply the section state explicitly to guarantee content is visible.
        binding.bottomNavigation.selectedItemId = initialNavId
        applySectionForNavItem(initialNavId)

        binding.btnLookup.setOnClickListener {
            val number = binding.etLookupNumber.text.toString().trim()
            performLookup(number, showNotification = false)
        }

        binding.etLookupNumber.doAfterTextChanged {
            binding.btnLookup.isEnabled = true
        }

        binding.btnClearSearch.setOnClickListener {
            cancelManualLookup()
            binding.etLookupNumber.text?.clear()
            binding.resultLayout.visibility = View.GONE
            binding.btnClearSearch.visibility = View.GONE
            latestLookupResult = null
        }

        binding.btnSave.setOnClickListener {
            val info = latestLookupResult
            if (info == null) {
                Toast.makeText(this, "No result to save", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveResultAsContact(info)
        }

        binding.btnCopy.setOnClickListener {
            val info = latestLookupResult
            if (info == null) {
                Toast.makeText(this, "No result to copy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            copyToClipboard(NotificationHelper.buildShareText(info))
        }

        binding.btnShare.setOnClickListener {
            val info = latestLookupResult
            if (info == null) {
                Toast.makeText(this, "No result to share", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            shareResult(NotificationHelper.buildShareText(info))
        }

        binding.btnDial.setOnClickListener {
            latestLookupResult?.let { startActivity(buildDialIntent(it.number)) }
        }
        binding.btnRefreshCaller.setOnClickListener {
            callerDiagnostics.record(CallerDiagnosticEvent.MANUAL_FORCE_REFRESH_REQUESTED)
            refreshCallerDiagnostics()
            val number = latestLookupResult?.number ?: binding.etLookupNumber.text?.toString().orEmpty()
            performLookup(number, showNotification = false, forceRefresh = true)
        }
        binding.btnEditCallerMetadata.setOnClickListener {
            latestLookupResult?.let(::showEditCallerMetadataDialog)
        }

        // About section listeners
        binding.cardOfficialWebsite.setOnClickListener {
            openUrl("https://github.com/belgareth/caller-info/releases")
        }
        binding.cardSourceCode.setOnClickListener {
            openUrl("https://github.com/belgareth/caller-info")
        }
        binding.btnInfoWebsite.setOnClickListener {
            openUrl("https://github.com/belgareth/caller-info/releases")
        }
        binding.btnInfoGithub.setOnClickListener {
            openUrl("https://github.com/belgareth/caller-info")
        }

        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.appVersionText.text = "Version ${pInfo.versionName}"
        } catch (_: Exception) {
            binding.appVersionText.text = ""
        }

        binding.permissionWarning.setOnClickListener {
            val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("enabled", false)
            if (!isEnabled && hasAllPermissions()) {
                binding.bottomNavigation.selectedItemId = R.id.nav_settings
            } else {
                promptForPermissions()
            }
        }

        binding.tvBatteryInfo.setOnClickListener {
            openBatteryBackgroundSettings()
        }

        checkPermissions()
    }

    private fun setupBottomContentClearance() {
        val content = binding.mainContentContainer
        initialMainContentPadding = Rect(
            content.paddingLeft,
            content.paddingTop,
            content.paddingRight,
            content.paddingBottom
        )

        binding.mainRoot.addOnLayoutChangeListener(bottomClearanceLayoutListener)
        binding.bottomNavigationContainer.addOnLayoutChangeListener(bottomClearanceLayoutListener)
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, insets ->
            scheduleBottomContentClearanceUpdate()
            insets
        }
        binding.mainRoot.doOnLayout { scheduleBottomContentClearanceUpdate() }
        binding.bottomNavigationContainer.doOnLayout { scheduleBottomContentClearanceUpdate() }
        ViewCompat.requestApplyInsets(binding.mainRoot)
    }

    private fun scheduleBottomContentClearanceUpdate() {
        if (!::binding.isInitialized) return
        binding.mainRoot.removeCallbacks(bottomClearanceUpdate)
        binding.mainRoot.post(bottomClearanceUpdate)
    }

    private fun updateBottomContentClearance() {
        val initialPadding = initialMainContentPadding ?: return
        val root = binding.mainRoot
        val bottomNavigation = binding.bottomNavigationContainer
        if (!root.isLaidOut || !bottomNavigation.isLaidOut) return

        val readableSpacing = (16f * resources.displayMetrics.density).toInt()
        val requiredBottomPadding = BottomNavigationClearance.requiredBottomPadding(
            rootHeight = root.height,
            bottomNavigationTop = bottomNavigation.top,
            stableContentBottomPadding = initialPadding.bottom,
            readableSpacing = readableSpacing
        )
        binding.mainContentContainer.updatePadding(
            left = initialPadding.left,
            top = initialPadding.top,
            right = initialPadding.right,
            bottom = requiredBottomPadding
        )
    }

    private fun observeTelegramState() {
        lifecycleScope.launch {
            telegramManager.authState.collectLatest { state ->
                updateLoginUi(state)
            }
        }
    }

    private fun updateLoginUi(state: TdApi.AuthorizationState) {
        binding.tilLoginCode.visibility = View.GONE
        binding.til2faPassword.visibility = View.GONE
        binding.btnLoginTelegram.isEnabled = true
        binding.btnDisconnectTelegram.visibility = View.GONE
        binding.btnDisconnectTelegram.isEnabled = true
        binding.ivLinkStatus.setImageResource(R.drawable.ic_unlinked)

        when (state.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                setLoginStatus("Enter API ID, App Hash and phone number, then tap Send Code.")
                binding.btnLoginTelegram.text = "Send Code"
                binding.llInputFields.visibility = View.VISIBLE
            }
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                // Never auto-submit from saved values; user must explicitly confirm login.
                setLoginStatus("Enter your phone number, then tap Send Code.")
                binding.btnLoginTelegram.text = "Send Code"
                binding.llInputFields.visibility = View.VISIBLE
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                setLoginStatus("Code sent. Enter the Telegram login code.")
                binding.tilLoginCode.visibility = View.VISIBLE
                binding.btnLoginTelegram.text = "Verify Code"
                binding.llInputFields.visibility = View.VISIBLE
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                setLoginStatus("2FA is enabled. Enter your password.")
                binding.til2faPassword.visibility = View.VISIBLE
                binding.btnLoginTelegram.text = "Submit Password"
                binding.llInputFields.visibility = View.VISIBLE
            }
            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> {
                setLoginBusy("Logging out...")
                binding.btnLoginTelegram.text = "Logging out..."
                binding.llInputFields.visibility = View.VISIBLE
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                setLoginStatus("Logged in successfully!")
                binding.btnLoginTelegram.text = getString(R.string.status_connected)
                binding.btnLoginTelegram.isEnabled = false
                binding.btnDisconnectTelegram.visibility = View.VISIBLE
                binding.llInputFields.visibility = View.GONE
                binding.ivLinkStatus.setImageResource(R.drawable.ic_linked)
                updateStatusIndicator(getSharedPreferences("Settings", MODE_PRIVATE).getBoolean("enabled", false))
                
                // Join groups/bots once logged in
                telegramManager.performInitialSetup()
            }
            else -> {
                setLoginStatus("Waiting for Telegram authorization state...")
                binding.btnLoginTelegram.text = "Login"
                binding.llInputFields.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_history, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val historyItem = menu.findItem(R.id.action_clear_all)
        historyItem?.isVisible = binding.bottomNavigation.selectedItemId == R.id.nav_history
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_all -> {
                showClearHistoryDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            getSharedPreferences("Settings", Context.MODE_PRIVATE).edit()
                .putInt("active_nav", item.itemId).apply()
            applySectionForNavItem(item.itemId)
        }
    }

    private fun applySectionForNavItem(itemId: Int): Boolean {
        when (itemId) {
            R.id.nav_lookup -> {
                binding.toolbar.title = getString(R.string.app_name)
                showSection(binding.searchLayout)
                invalidateOptionsMenu()
                return true
            }
            R.id.nav_history -> {
                binding.toolbar.title = "Search History"
                showSection(binding.historyLayout)
                loadHistory()
                invalidateOptionsMenu()
                return true
            }
            R.id.nav_settings -> {
                binding.toolbar.title = "Settings"
                showSection(binding.settingsLayout)
                refreshPendingLookupStatus()
                invalidateOptionsMenu()
                return true
            }
            R.id.nav_info -> {
                binding.toolbar.title = "About"
                showSection(binding.infoLayout)
                invalidateOptionsMenu()
                return true
            }
            else -> return false
        }
    }

    private fun setupHistory() {
        historyAdapter = HistoryAdapter(
            items = emptyList(),
            onSave = { info -> saveResultAsContact(info) },
            onCopy = { info -> copyToClipboard(NotificationHelper.buildShareText(info)) },
            onShare = { info -> shareResult(NotificationHelper.buildShareText(info)) },
            onDelete = { info ->
                lifecycleScope.launch {
                    repository.deleteHistoryItem(info.number)
                    loadHistory()
                }
            },
            onDial = { info -> startActivity(buildDialIntent(info.number)) },
            onFavorite = { info ->
                lifecycleScope.launch {
                    repository.setFavorite(info.number, !info.favorite)
                    loadHistory()
                }
            },
            onEdit = ::showEditCallerMetadataDialog,
            normalizeNumber = repository::sanitizeNumber
        )
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter
        binding.rvHistory.isNestedScrollingEnabled = false
        binding.rvHistory.itemAnimator = null
        binding.etHistorySearch.doAfterTextChanged {
            historyFilter = historyFilter.copy(query = it?.toString().orEmpty())
            applyHistoryFilter()
        }
        binding.btnHistoryFavoritesFilter.setOnClickListener {
            historyFilter = historyFilter.copy(favoritesOnly = !historyFilter.favoritesOnly)
            binding.btnHistoryFavoritesFilter.isChecked = historyFilter.favoritesOnly
            applyHistoryFilter()
        }
        binding.btnHistoryRecentFilter.setOnClickListener {
            historyFilter = historyFilter.copy(recentOnly = !historyFilter.recentOnly)
            binding.btnHistoryRecentFilter.isChecked = historyFilter.recentOnly
            applyHistoryFilter()
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            allHistoryItems = repository.getAllHistory()
            applyHistoryFilter()
        }
    }

    private fun applyHistoryFilter() {
        if (!::historyAdapter.isInitialized) return
        val visible = filterHistory(
            allHistoryItems,
            historyFilter,
            System.currentTimeMillis(),
            normalizeNumber = repository::sanitizeNumber
        )
        if (visible.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.rvHistory.visibility = View.GONE
            binding.historyEmptyMessage.text = if (allHistoryItems.isEmpty()) {
                getString(R.string.empty_history_subtitle)
            } else {
                getString(R.string.no_history_matches)
            }
        } else {
            binding.emptyState.visibility = View.GONE
            binding.rvHistory.visibility = View.VISIBLE
            historyAdapter.updateData(visible)
        }
    }

    private fun showEditCallerMetadataDialog(info: CallerInfoEntity) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }
        val aliasInput = android.widget.EditText(this).apply {
            hint = getString(R.string.alias_hint)
            setText(info.userAlias.orEmpty())
        }
        val noteInput = android.widget.EditText(this).apply {
            hint = getString(R.string.note_hint)
            setText(info.userNote.orEmpty())
            minLines = 2
        }
        container.addView(aliasInput)
        container.addView(noteInput)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_alias_note)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save_metadata) { _, _ ->
                lifecycleScope.launch {
                    val alias = aliasInput.text?.toString()
                    val note = noteInput.text?.toString()
                    val updated = repository.updateUserMetadata(
                        info.number, alias, note, info.favorite
                    )
                    if (updated && latestLookupResult?.number == info.number) {
                        latestLookupResult = info.copy(
                            userAlias = alias?.trim()?.takeIf(String::isNotBlank),
                            userNote = note?.trim()?.takeIf(String::isNotBlank)
                        )
                        latestLookupResult?.let(::updateResultUI)
                    }
                    loadHistory()
                }
            }
            .show()
    }

    private fun showClearHistoryDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Clear History")
            .setMessage("Are you sure you want to clear all search history?")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    repository.clearHistory()
                    loadHistory()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSetupSection() {
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val numberFormattingPreferences = NumberFormattingPreferences.getInstance(this)
        
        // Initialize connection illustration - always visible
        binding.llInputFields.visibility = View.VISIBLE
        binding.ivLinkStatus.setImageResource(R.drawable.ic_unlinked)
        
        binding.switchEnable.isChecked = prefs.getBoolean("enabled", false)
        binding.switchLookupKnown.isChecked = prefs.getBoolean("lookup_known", false)
        binding.etCallingCode.setText(numberFormattingPreferences.getCallingCode())
        binding.etLocalPrefix.setText(numberFormattingPreferences.getLocalPrefix())
        binding.etNationalNumberLength.setText(numberFormattingPreferences.getNationalNumberLength())
        binding.switchAcceptWithoutPrefix.isChecked =
            numberFormattingPreferences.getAcceptWithoutPrefix()
        val recentCallPreferences = RecentCallPreferences.getInstance(this)
        binding.switchShowPreviousCall.isChecked = recentCallPreferences.isEnabled()
        val lookupSourcePreferences = LookupSourcePreferences.getInstance(this)
        binding.switchShowLookupSource.isChecked = lookupSourcePreferences.isEnabled()
        val test15Preferences = Test15Preferences.getInstance(this)
        val freshnessLabels = listOf(
            getString(R.string.cache_7_days),
            getString(R.string.cache_30_days),
            getString(R.string.cache_90_days)
        )
        binding.etCacheFreshness.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, freshnessLabels)
        )
        binding.etCacheFreshness.setText(
            freshnessLabels[when (test15Preferences.cacheFreshness()) {
                CacheFreshness.SEVEN -> 0
                CacheFreshness.THIRTY -> 1
                CacheFreshness.NINETY -> 2
            }], false
        )
        binding.etCacheFreshness.setOnItemClickListener { _, _, position, _ ->
            test15Preferences.setCacheFreshness(
                listOf(CacheFreshness.SEVEN, CacheFreshness.THIRTY, CacheFreshness.NINETY)[position]
            )
        }

        val sizeLabels = listOf(getString(R.string.card_compact), getString(R.string.card_expanded))
        binding.etCallerCardSize.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, sizeLabels)
        )
        binding.etCallerCardSize.setText(
            sizeLabels[if (test15Preferences.callerCardSize() == CallerCardSize.COMPACT) 0 else 1], false
        )
        binding.etCallerCardSize.setOnItemClickListener { _, _, position, _ ->
            test15Preferences.setCallerCardSize(if (position == 0) CallerCardSize.COMPACT else CallerCardSize.EXPANDED)
        }

        val positionLabels = listOf(
            getString(R.string.card_upper), getString(R.string.card_center), getString(R.string.card_lower)
        )
        binding.etCallerCardPosition.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, positionLabels)
        )
        binding.etCallerCardPosition.setText(
            positionLabels[when (test15Preferences.callerCardPosition()) {
                CallerCardPosition.UPPER -> 0
                CallerCardPosition.CENTER -> 1
                CallerCardPosition.LOWER -> 2
            }], false
        )
        binding.etCallerCardPosition.setOnItemClickListener { _, _, position, _ ->
            test15Preferences.setCallerCardPosition(
                listOf(CallerCardPosition.UPPER, CallerCardPosition.CENTER, CallerCardPosition.LOWER)[position]
            )
        }

        fun saveNumberFormattingSettings() {
            val callingCode = binding.etCallingCode.text?.toString().orEmpty()
            val localPrefix = binding.etLocalPrefix.text?.toString().orEmpty()
            val nationalLengthText = binding.etNationalNumberLength.text?.toString().orEmpty()
            val nationalLength = nationalLengthText.toIntOrNull()
            val acceptWithoutPrefix = binding.switchAcceptWithoutPrefix.isChecked
            val profileRequested =
                callingCode.isNotEmpty() ||
                    localPrefix.isNotEmpty() ||
                    nationalLengthText.isNotEmpty() ||
                    acceptWithoutPrefix

            val callingCodeValid = isValidCallingCode(callingCode)
            val localPrefixValid = isValidLocalPrefix(localPrefix)
            val nationalLengthValid = nationalLength != null && nationalLength > 0

            binding.tilCallingCode.error = when {
                callingCode.isEmpty() && profileRequested ->
                    getString(R.string.error_calling_code_required)
                callingCode.isNotEmpty() && !callingCodeValid ->
                    getString(R.string.error_calling_code_invalid)
                else -> null
            }
            binding.tilLocalPrefix.error =
                if (localPrefixValid) null else getString(R.string.error_local_prefix_invalid)
            binding.tilNationalNumberLength.error = when {
                nationalLengthText.isEmpty() && profileRequested ->
                    getString(R.string.error_national_length_required)
                nationalLengthText.isNotEmpty() && !nationalLengthValid ->
                    getString(R.string.error_national_length_invalid)
                callingCodeValid &&
                    nationalLengthValid &&
                    !isValidNationalNumberLength(callingCode, nationalLength) ->
                    getString(R.string.error_international_length)
                else -> null
            }

            numberFormattingPreferences.save(
                callingCode = callingCode,
                localPrefix = localPrefix,
                nationalNumberLength = nationalLengthText,
                acceptWithoutPrefix = acceptWithoutPrefix
            )
        }

        binding.etCallingCode.doAfterTextChanged { saveNumberFormattingSettings() }
        binding.etLocalPrefix.doAfterTextChanged { saveNumberFormattingSettings() }
        binding.etNationalNumberLength.doAfterTextChanged { saveNumberFormattingSettings() }
        binding.switchAcceptWithoutPrefix.setOnCheckedChangeListener { _, _ ->
            saveNumberFormattingSettings()
        }
        binding.etNumberFormattingPreview.doAfterTextChanged {
            binding.tvNumberFormattingPreviewResult.visibility = View.GONE
        }
        binding.btnPreviewNumberFormatting.setOnClickListener {
            saveNumberFormattingSettings()
            val result = previewNumberFormatting(
                input = binding.etNumberFormattingPreview.text?.toString(),
                config = numberFormattingPreferences.getConfig()
            )
            binding.tvNumberFormattingPreviewResult.text = if (result.isValid) {
                getString(R.string.formatted_result, result.formattedNumber)
            } else {
                getString(R.string.unable_to_format)
            }
            binding.tvNumberFormattingPreviewResult.visibility = View.VISIBLE
        }
        binding.switchShowPreviousCall.setOnCheckedChangeListener { _, isChecked ->
            if (updatingRecentCallSwitch) return@setOnCheckedChangeListener
            recentCallPreferences.setEnabled(isChecked)
            refreshAppStatus()
        }
        binding.switchShowLookupSource.setOnCheckedChangeListener { _, isChecked ->
            lookupSourcePreferences.setEnabled(isChecked)
        }
        binding.btnClearSavedCallerInfo.setOnClickListener {
            showClearSavedCallerInformationDialog()
        }
        binding.btnExportCallerData.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_caller_data)
                .setMessage(R.string.export_privacy_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.export_caller_data) { _, _ ->
                    exportCallerDataLauncher.launch("caller-info-test15-export.json")
                }
                .show()
        }
        binding.btnImportCallerData.setOnClickListener {
            importCallerDataLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
        }
        binding.btnRetryPendingLookups.setOnClickListener {
            lifecycleScope.launch {
                val pendingCount = repository.pendingLookupCount()
                when {
                    pendingCount == 0 -> Toast.makeText(
                        this@MainActivity,
                        R.string.retry_pending_none,
                        Toast.LENGTH_SHORT
                    ).show()
                    repository.retryPendingLookupsNow() -> Toast.makeText(
                        this@MainActivity,
                        R.string.retry_pending_started,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                refreshPendingLookupStatus()
            }
        }
        binding.btnClearPendingLookups.setOnClickListener {
            showClearPendingLookupsDialog()
        }
        refreshPendingLookupStatus()

        val historyOptions = arrayOf("100", "1000", "2000", "5000", "10000", "20000", "Unlimited")
        val historyAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, historyOptions)
        binding.etMaxHistory.setAdapter(historyAdapter)
        
        binding.etMaxHistory.setText(prefs.getString("max_history_size", "1000"), false)
        binding.etMaxHistory.doAfterTextChanged { text ->
            prefs.edit().putString("max_history_size", text?.toString() ?: "1000").apply()
        }

        val storedCredentials = telegramManager.savedCredentials()
        binding.etAppId.setText(
            storedCredentials?.apiId?.takeIf { it != 0 }?.toString().orEmpty()
        )
        binding.etAppHash.setText(storedCredentials?.apiHash.orEmpty())
        binding.etTelegramPhone.setText(storedCredentials?.phone.orEmpty())

        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        when (themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> binding.toggleTheme.check(R.id.btnThemeLight)
            AppCompatDelegate.MODE_NIGHT_YES -> binding.toggleTheme.check(R.id.btnThemeDark)
            else -> binding.toggleTheme.check(R.id.btnThemeSystem)
        }

        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnThemeLight -> AppCompatDelegate.MODE_NIGHT_NO
                    R.id.btnThemeDark -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                prefs.edit().putInt("theme_mode", mode).apply()
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }

        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            if (updatingCallerIdSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                if (!telegramManager.isReady()) {
                    setCallerIdEnabled(false)
                    binding.bottomNavigation.selectedItemId = R.id.nav_settings
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Setup Required")
                        .setMessage("Please complete the Telegram login first before enabling Caller ID.")
                        .setPositiveButton("OK", null)
                        .show()
                    return@setOnCheckedChangeListener
                }
                
                if (!hasAllPermissions()) {
                    setCallerIdEnabled(false)
                    promptForPermissions()
                    return@setOnCheckedChangeListener
                }

                val decision = currentCallerIdRoleDecision(enableRequested = true)
                if (decision.requestRole) {
                    setCallerIdEnabled(false)
                    enableAfterRoleRequest = true
                    requestCallerScreeningRole()
                    return@setOnCheckedChangeListener
                }
                if (!decision.enabled) {
                    setCallerIdEnabled(false)
                    Toast.makeText(this, "Call Screening is unavailable on this device.", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
            }
            setCallerIdEnabled(isChecked)
        }

        binding.btnCopyCallerDiagnostics.setOnClickListener {
            copyToClipboard(callerDiagnostics.summary())
        }
        binding.btnClearCallerDiagnostics.setOnClickListener {
            callerDiagnostics.clear()
            refreshCallerDiagnostics()
            Toast.makeText(this, R.string.caller_diagnostics_cleared, Toast.LENGTH_SHORT).show()
        }
        refreshCallerDiagnostics()

        binding.switchLookupKnown.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("lookup_known", isChecked).apply()
        }

        binding.btnLoginTelegram.setOnClickListener {
            handleTelegramLogin()
        }
        binding.btnDisconnectTelegram.setOnClickListener {
            showTelegramDisconnectConfirmation()
        }
        binding.btnPreviewCallerCard.setOnClickListener {
            if (hasOverlayPermission()) {
                CallerOverlayService.showPreview(applicationContext)
            } else {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.preview_caller_card)
                    .setMessage(R.string.preview_overlay_permission)
                    .setPositiveButton(R.string.status_action_open_settings) { _, _ ->
                        requestOverlayPermission()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        refreshAppStatus()
    }

    private fun handleTelegramLogin() {
        val currentState = telegramManager.authState.replayCache.firstOrNull()
        if (currentState == null) {
            setLoginStatus("Telegram is initializing. Please try again.", isError = true)
            return
        }

        val appIdStr = binding.etAppId.text.toString().trim()
        val appHash = binding.etAppHash.text.toString().trim()
        val phone = binding.etTelegramPhone.text.toString().trim()
        val appId = appIdStr.toIntOrNull()

        when (currentState.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                if (appId == null || appHash.isEmpty()) {
                    setLoginStatus("Valid API ID and App Hash are required.", isError = true)
                    return
                }
                if (!saveTelegramCredentials(apiId = appId, apiHash = appHash, phone = phone)) {
                    setLoginStatus(
                        "Secure configuration is unavailable. Re-enter sign-in details.",
                        isError = true
                    )
                    return
                }
                setLoginBusy("Connecting to Telegram...")
                telegramManager.sendTdlibParameters(appId, appHash)
            }
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                if (phone.isEmpty()) {
                    setLoginStatus("Phone number is required.", isError = true)
                    return
                }
                if (appId != null && appHash.isNotEmpty()) {
                    if (!saveTelegramCredentials(apiId = appId, apiHash = appHash, phone = phone)) {
                        setLoginStatus(
                            "Secure configuration is unavailable. Re-enter sign-in details.",
                            isError = true
                        )
                        return
                    }
                }
                setLoginBusy("Requesting login code...")
                telegramManager.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) { result ->
                    runOnUiThread {
                        handleAuthActionResult(result, "request code")
                    }
                }
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                val code = binding.etLoginCode.text.toString().trim()
                if (code.isEmpty()) {
                    setLoginStatus("Please enter the login code.", isError = true)
                    return
                }
                setLoginBusy("Verifying code...")
                telegramManager.send(TdApi.CheckAuthenticationCode(code)) { result ->
                    runOnUiThread {
                        handleAuthActionResult(result, "verify code")
                    }
                }
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                val password = binding.et2faPassword.text.toString().trim()
                if (password.isEmpty()) {
                    setLoginStatus("Please enter your 2FA password.", isError = true)
                    return
                }
                setLoginBusy("Verifying password...")
                telegramManager.send(TdApi.CheckAuthenticationPassword(password)) { result ->
                    runOnUiThread {
                        handleAuthActionResult(result, "verify password")
                    }
                }
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                setLoginStatus(getString(R.string.telegram_connected_disconnect_advanced))
            }
            else -> setLoginStatus("Telegram state is not ready for this action.", isError = true)
        }
    }

    private fun showTelegramDisconnectConfirmation() {
        if (!telegramManager.isReady()) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disconnect_telegram)
            .setMessage(R.string.disconnect_telegram_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.disconnect_telegram) { _, _ ->
                setLoginBusy(getString(R.string.logging_out_telegram))
                binding.btnDisconnectTelegram.isEnabled = false
                telegramManager.send(TdApi.LogOut()) { result ->
                    runOnUiThread {
                        if (result is TdApi.Error) {
                            binding.btnDisconnectTelegram.visibility = View.VISIBLE
                            binding.btnDisconnectTelegram.isEnabled = true
                            setLoginStatus(getString(R.string.logout_failed), isError = true)
                        } else {
                            clearTelegramCredentialsAndInputs()
                            setLoginStatus(getString(R.string.logged_out_reauth))
                        }
                    }
                }
            }
            .show()
    }

    private fun handleAuthActionResult(result: TdApi.Object, action: String) {
        binding.btnLoginTelegram.isEnabled = true
        if (result is TdApi.Error) {
            setLoginStatus("Could not $action. Try again.", isError = true)
        }
        // State change will trigger updateLoginUi with proper messaging;
        // don't show intermediate "waiting" message.
    }

    private fun saveTelegramCredentials(apiId: Int, apiHash: String, phone: String): Boolean =
        telegramManager.saveCredentials(TelegramCredentials(apiId, apiHash, phone))

    private fun clearTelegramCredentialsAndInputs() {
        telegramManager.clearCredentials()

        binding.etAppId.text?.clear()
        binding.etAppHash.text?.clear()
        binding.etTelegramPhone.text?.clear()
        binding.etLoginCode.text?.clear()
        binding.et2faPassword.text?.clear()
        binding.tilLoginCode.visibility = View.GONE
        binding.til2faPassword.visibility = View.GONE
        binding.llInputFields.visibility = View.VISIBLE
        binding.btnLoginTelegram.isEnabled = true
        binding.btnLoginTelegram.text = "Send Code"
        binding.btnDisconnectTelegram.visibility = View.GONE
        binding.ivLinkStatus.setImageResource(R.drawable.ic_unlinked)
        binding.switchEnable.isChecked = false
    }

    private fun setLoginBusy(message: String) {
        binding.btnLoginTelegram.isEnabled = false
        setLoginStatus(message)
    }

    private fun setLoginStatus(message: String, isError: Boolean = false) {
        binding.tvLoginStatus.text = message
        val colorAttr = if (isError) {
            com.google.android.material.R.attr.colorError
        } else {
            com.google.android.material.R.attr.colorPrimary
        }
        binding.tvLoginStatus.setTextColor(MaterialColors.getColor(binding.tvLoginStatus, colorAttr))
    }

    private fun showSection(targetView: View) {
        val views = listOf(binding.searchLayout, binding.historyLayout, binding.settingsLayout, binding.infoLayout)
        val currentIndex = views.indexOfFirst { it.visibility == View.VISIBLE }
        val targetIndex = views.indexOf(targetView)

        if (currentIndex == targetIndex) return

        // Cancel previous vertical scroll state and go to the top
        binding.mainScrollView.scrollTo(0, 0)
        binding.appBarLayout.setExpanded(true, false)

        val screenWidth = resources.displayMetrics.widthPixels.toFloat() / 2f 

        views.forEachIndexed { index, view ->
            if (view == targetView) {
                if (view.visibility != View.VISIBLE) {
                    view.visibility = View.VISIBLE
                    view.alpha = 0f

                    if (currentIndex != -1) {
                        view.translationX = if (targetIndex > currentIndex) screenWidth else -screenWidth
                    } else {
                        view.translationX = 0f
                        view.translationY = 30f
                    }

                    view.animate()
                        .alpha(1f)
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(300)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction(null)
                        .start()
                }
            } else {
                if (view.visibility == View.VISIBLE) {
                    val distance = if (targetIndex > currentIndex) -screenWidth else screenWidth
                    view.animate()
                        .alpha(0f)
                        .translationX(distance)
                        .setDuration(300)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction { 
                            view.visibility = View.GONE
                            view.translationX = 0f
                        }
                        .start()
                } else {
                    view.visibility = View.GONE
                }
            }
        }
    }


    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun currentCallerIdRoleDecision(enableRequested: Boolean): CallerIdRoleDecision {
        val roleRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val roleManager = if (roleRequired) getSystemService(RoleManager::class.java) else null
        val roleAvailable = roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
        val roleHeld = roleAvailable && roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        return callerIdRoleDecision(enableRequested, roleRequired, roleAvailable, roleHeld)
    }

    private fun isCallerScreeningRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val roleManager = getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun requestCallerScreeningRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return
        roleRequestLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    private fun setCallerIdEnabled(enabled: Boolean) {
        getSharedPreferences("Settings", MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        updatingCallerIdSwitch = true
        binding.switchEnable.isChecked = enabled
        updatingCallerIdSwitch = false
        updateStatusIndicator(enabled)
        if (enabled && networkCallback == null) setupNetworkListener()
    }

    private fun reconcileCallerIdRoleState() {
        val prefs = getSharedPreferences("Settings", MODE_PRIVATE)
        val persisted = prefs.getBoolean("enabled", false)
        val reconciled = currentCallerIdRoleDecision(persisted).enabled
        if (persisted != reconciled || binding.switchEnable.isChecked != reconciled) {
            setCallerIdEnabled(reconciled)
        }
    }

    private fun refreshCallerDiagnostics() {
        if (!::binding.isInitialized || !::callerDiagnostics.isInitialized) return
        binding.tvCallerDiagnostics.text = callerDiagnostics.summary(limit = 12)
    }

    private fun refreshAppStatus() {
        if (!::binding.isInitialized) return

        val roleManager = getSystemService(RoleManager::class.java)
        val roleAvailable =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                roleManager?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
        val notificationsRelevant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val powerManager = getSystemService(PowerManager::class.java)
        val lastRemoteMillis = repository.lastSuccessfulRemoteLookupMillis()
        val lastRemoteText = lastRemoteMillis?.let {
            android.text.format.DateUtils.getRelativeTimeSpanString(
                it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
            ).toString()
        }
        val snapshot = AppStatusSnapshot(
            callerScreeningAvailable = roleAvailable,
            callerScreeningActive =
                roleAvailable && roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true,
            overlayAllowed = hasOverlayPermission(),
            phoneAllowed = isPermissionAllowed(android.Manifest.permission.READ_PHONE_STATE),
            contactsAllowed = isPermissionAllowed(android.Manifest.permission.READ_CONTACTS),
            previousCallContextEnabled = RecentCallPreferences.getInstance(this).isEnabled(),
            notificationsRelevant = notificationsRelevant,
            notificationsAllowed =
                !notificationsRelevant ||
                    isPermissionAllowed(android.Manifest.permission.POST_NOTIFICATIONS),
            telegramReady = telegramManager.isReady(),
            batteryOptimizationStatus = batteryOptimizationStatus(
                sdkInt = Build.VERSION.SDK_INT,
                ignoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    powerManager?.isIgnoringBatteryOptimizations(packageName)
                } else null
            ),
            backgroundRestrictionStatus = backgroundRestrictionStatus(
                sdkInt = Build.VERSION.SDK_INT,
                backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
                } else null
            ),
            pendingLookupCount = pendingLookupCountForStatus,
            lastRemoteLookupText = lastRemoteText
        )

        binding.statusItemsContainer.removeAllViews()
        buildAppStatusItems(snapshot).forEach { item ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.layout_app_status_item, binding.statusItemsContainer, false)
            row.findViewById<android.widget.TextView>(R.id.tvStatusName).text =
                getString(statusNameResource(item.type))
            row.findViewById<android.widget.TextView>(R.id.tvStatusValue).text =
                statusValueText(item)

            val actionButton =
                row.findViewById<com.google.android.material.button.MaterialButton>(
                    R.id.btnStatusAction
                )
            if (item.action == AppStatusAction.NONE) {
                actionButton.visibility = View.GONE
            } else {
                actionButton.text = getString(statusActionResource(item.action))
                actionButton.setOnClickListener { performStatusAction(item) }
            }
            binding.statusItemsContainer.addView(row)
        }
    }

    private fun performStatusAction(item: AppStatusItem) {
        when (item.type) {
            AppStatusType.CALLER_SCREENING -> {
                enableAfterRoleRequest = false
                requestCallerScreeningRole()
            }
            AppStatusType.OVERLAY -> requestOverlayPermission()
            AppStatusType.PHONE ->
                requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 101)
            AppStatusType.CONTACTS ->
                requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), 101)
            AppStatusType.PREVIOUS_CALL_CONTEXT -> Unit
            AppStatusType.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                        101
                    )
                }
            }
            AppStatusType.BATTERY_OPTIMIZATION,
            AppStatusType.BACKGROUND_RESTRICTION -> openBatteryBackgroundSettings()
            AppStatusType.TELEGRAM,
            AppStatusType.PENDING_LOOKUPS,
            AppStatusType.LAST_REMOTE_LOOKUP -> Unit
        }
    }

    private fun isPermissionAllowed(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun statusNameResource(type: AppStatusType): Int = when (type) {
        AppStatusType.CALLER_SCREENING -> R.string.status_caller_screening
        AppStatusType.OVERLAY -> R.string.status_overlay
        AppStatusType.PHONE -> R.string.status_phone
        AppStatusType.CONTACTS -> R.string.status_contacts
        AppStatusType.PREVIOUS_CALL_CONTEXT -> R.string.status_previous_call_context
        AppStatusType.NOTIFICATIONS -> R.string.status_notifications
        AppStatusType.TELEGRAM -> R.string.status_telegram
        AppStatusType.BATTERY_OPTIMIZATION -> R.string.status_battery_optimization
        AppStatusType.BACKGROUND_RESTRICTION -> R.string.status_background_restriction
        AppStatusType.PENDING_LOOKUPS -> R.string.status_pending_lookups
        AppStatusType.LAST_REMOTE_LOOKUP -> R.string.status_last_remote_lookup
    }

    private fun statusValueText(item: AppStatusItem): String {
        item.detail?.let { return it }
        val value = getString(
            when (item.value) {
                AppStatusValue.ACTIVE -> R.string.status_active
                AppStatusValue.ALLOWED -> R.string.status_allowed
                AppStatusValue.NOT_ALLOWED -> R.string.status_not_allowed
                AppStatusValue.NOT_SELECTED -> R.string.status_not_selected
                AppStatusValue.OPTIONAL -> R.string.status_optional
                AppStatusValue.UNAVAILABLE -> R.string.status_unavailable
                AppStatusValue.DEFAULT -> R.string.status_default
                AppStatusValue.EXEMPT -> R.string.status_exempt
                AppStatusValue.RESTRICTED -> R.string.status_restricted
                AppStatusValue.NOT_RESTRICTED -> R.string.status_not_restricted
                AppStatusValue.CONNECTED -> R.string.status_connected
                AppStatusValue.DISCONNECTED -> R.string.status_disconnected
                AppStatusValue.INFO -> R.string.status_unavailable
            }
        )
        return if (item.optional && item.value != AppStatusValue.OPTIONAL) {
            getString(R.string.status_optional_suffix, value)
        } else {
            value
        }
    }

    private fun statusActionResource(action: AppStatusAction): Int = when (action) {
        AppStatusAction.SELECT -> R.string.status_action_select
        AppStatusAction.ALLOW -> R.string.status_action_allow
        AppStatusAction.OPEN_SETTINGS -> R.string.status_action_open_settings
        AppStatusAction.NONE -> error("No action label")
    }

    private fun permissionName(permission: String): String = when (permission) {
        android.Manifest.permission.READ_PHONE_STATE -> "Phone"
        android.Manifest.permission.READ_CONTACTS -> "Contacts"
        android.Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        else -> permission.substringAfterLast(".")
    }

    private fun hasAllPermissions(): Boolean {
        val permissions = mutableListOf(
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.ACCESS_NETWORK_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return missingPermissions.isEmpty() && hasOverlayPermission()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        val overlayMissing = !hasOverlayPermission()
        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("enabled", false)

        if (missingPermissions.isNotEmpty() || overlayMissing || !isEnabled) {
            binding.permissionWarning.visibility = View.VISIBLE

            val issues = mutableListOf<String>()
            if (!isEnabled) issues.add("Caller ID is disabled")

            if (missingPermissions.isNotEmpty()) {
                val names = missingPermissions.joinToString(", ") { perm ->
                    when (perm) {
                        android.Manifest.permission.READ_PHONE_STATE -> "Phone"
                        android.Manifest.permission.READ_CONTACTS -> "Contacts"
                        android.Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
                        else -> perm.substringAfterLast(".")
                    }
                }
                issues.add("Missing permissions: $names")
            }
            if (overlayMissing) issues.add("Overlay required")
            
            val text = "${issues.joinToString(". ")}. Tap here to fix."
            val spannableString = android.text.SpannableString(text)
            val tapIndex = text.indexOf("Tap here")
            if (tapIndex != -1) {
                spannableString.setSpan(android.text.style.UnderlineSpan(), tapIndex, text.length, 0)
            }
            binding.permissionWarning.text = spannableString
        } else {
            binding.permissionWarning.visibility = View.GONE
        }
        
        // Ensure network listener is active if permission is granted
        if (networkCallback == null && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED) {
            setupNetworkListener()
        }
    }

    private fun promptForPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            val nextPermission = missingPermissions.first()
            val name = permissionName(nextPermission)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Permissions Required")
                .setMessage("Please allow the $name permission.")
                .setPositiveButton("Allow") { _, _ -> 
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        requestPermissions(arrayOf(nextPermission), 101)
                    }
                }
                .setNeutralButton("App Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (!hasOverlayPermission()) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Overlay Permission")
                .setMessage("To automatically show Caller ID over other apps during an incoming call, please allow the 'Display over other apps' permission.")
                .setPositiveButton("Open Settings") { _, _ -> requestOverlayPermission() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    
    private fun openBatteryBackgroundSettings() {
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.battery_background_settings)
            .setMessage(R.string.battery_background_guidance)
            .setPositiveButton(R.string.battery_background_app_settings) { _, _ ->
                startActivity(
                    intentForBatteryBackgroundDestination(
                        BatteryBackgroundSettingsDestination.APP_DETAILS
                    )
                )
            }
            .setNegativeButton("Cancel", null)

        if (batteryBackgroundSettingsDestinations(Build.VERSION.SDK_INT).contains(
                BatteryBackgroundSettingsDestination.BATTERY_OPTIMIZATION
            )
        ) {
            builder.setNeutralButton(R.string.battery_background_optimization_settings) { _, _ ->
                startActivity(
                    intentForBatteryBackgroundDestination(
                        BatteryBackgroundSettingsDestination.BATTERY_OPTIMIZATION
                    )
                )
            }
        }

        builder.show()
    }

    private fun intentForBatteryBackgroundDestination(
        destination: BatteryBackgroundSettingsDestination
    ): Intent {
        val primary = when (destination) {
            BatteryBackgroundSettingsDestination.APP_DETAILS ->
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            BatteryBackgroundSettingsDestination.BATTERY_OPTIMIZATION ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                }
        }
        return if (primary.resolveActivity(packageManager) != null) {
            primary
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(getString(R.string.app_name), text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareResult(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }

    private fun saveResultAsContact(info: CallerInfoEntity) {
        val displayName = info.displayName()?.takeIf { it.isNotBlank() } ?: "Unknown"
        val insertIntent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra(ContactsContract.Intents.Insert.NAME, displayName)
            putExtra(ContactsContract.Intents.Insert.PHONE, info.number)
        }
        startActivity(insertIntent)
    }

    private fun performLookup(number: String, showNotification: Boolean, forceRefresh: Boolean = false) {
        if (number.isEmpty()) {
            Toast.makeText(this, "Enter a number first", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnLookup.isEnabled = false
        binding.resultLayout.visibility = View.VISIBLE
        binding.btnClearSearch.visibility = View.VISIBLE
        with(binding.cardContent) {
            tvName.text = "Searching..."
            tvNumber.text = number
            tvCarrier.text = ""
            tvEmail.visibility = View.GONE
            tvLocation.visibility = View.GONE
            tvAddress.visibility = View.GONE
            tvError.visibility = View.GONE
        }

        cancelManualLookup()
        val generation = manualLookupGeneration.begin()
        if (forceRefresh) {
            callerDiagnostics.record(
                CallerDiagnosticEvent.MANUAL_GENERATION_CREATED,
                generation = generation
            )
            refreshCallerDiagnostics()
        }
        activeManualLookupGeneration = generation
        manualLookupJob = lifecycleScope.launch {
            try {
                var localDisplayed = false
                val lookupResult = repository.getCallerInfoWithSource(
                    rawNumber = number,
                    onLocalResult = { localResult ->
                        if (manualLookupGeneration.isCurrent(generation)) {
                            localDisplayed = true
                            updateResultUI(localResult.callerInfo)
                        }
                    },
                    requestStillValid = {
                        manualLookupGeneration.isCurrent(generation)
                    },
                    forceRemoteRefresh = forceRefresh
                )
                if (!manualLookupGeneration.isCurrent(generation)) return@launch

                val result = lookupResult.callerInfo
                if (!localDisplayed || lookupResult.source == CallerLookupSource.REMOTE) {
                    updateResultUI(result)
                }

                if (showNotification && manualLookupGeneration.isCurrent(generation)) {
                    val message = NotificationHelper.buildNotificationMessage(result)
                    NotificationHelper.showNotification(
                        this@MainActivity,
                        "Result",
                        message,
                        result = result
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (manualLookupGeneration.isCurrent(generation)) {
                    binding.btnLookup.isEnabled = true
                    manualLookupGeneration.invalidate(generation)
                    activeManualLookupGeneration = null
                    refreshCallerDiagnostics()
                }
            }
        }
    }

    private fun cancelManualLookup() {
        manualLookupJob?.cancel()
        manualLookupJob = null
        activeManualLookupGeneration?.let(manualLookupGeneration::invalidate)
        activeManualLookupGeneration = null
    }

    private fun updateResultUI(info: CallerInfoEntity) {
        latestLookupResult = info
        with(binding.cardContent) {
            tvName.text = info.displayName() ?: "Unknown"
            tvNumber.text = info.number
            
            val carrierText = listOfNotNull(info.carrier, info.country).joinToString(", ")
            tvCarrier.text = if (carrierText.isNotEmpty()) carrierText else "Unknown Carrier"
            
            if (!info.email.isNullOrEmpty()) {
                tvEmail.text = info.email
                tvEmail.visibility = View.VISIBLE
            } else {
                tvEmail.visibility = View.GONE
            }

            if (!info.location.isNullOrEmpty()) {
                tvLocation.text = info.location
                tvLocation.visibility = View.VISIBLE
            } else {
                tvLocation.visibility = View.GONE
            }

            val fullAddress = listOfNotNull(info.address1, info.address2).joinToString("\n")
            tvUserNote.visibility = if (!info.userNote.isNullOrBlank()) View.VISIBLE else View.GONE
            if (!info.userNote.isNullOrBlank()) tvUserNote.text = info.userNote
            tvTime.visibility = View.VISIBLE
            tvTime.text = info.lastSuccessfullyUpdatedMillis?.let {
                "Updated " + android.text.format.DateUtils.getRelativeTimeSpanString(
                    it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                )
            } ?: "Not refreshed"
            if (fullAddress.isNotEmpty()) {
                tvAddress.text = fullAddress
                tvAddress.visibility = View.VISIBLE
            } else {
                tvAddress.visibility = View.GONE
            }
            
            if (info.error != null) {
                 tvError.text = info.error
                 tvError.visibility = View.VISIBLE
            } else {
                 tvError.visibility = View.GONE
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            checkPermissions()
            refreshAppStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        OfflineLookupScheduler.enqueue(applicationContext)
        if (::binding.isInitialized) {
            reconcileCallerIdRoleState()
            checkPermissions()
            val prefs = getSharedPreferences("Settings", MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("enabled", false)
            updateStatusIndicator(isEnabled)
            refreshAppStatus()
            refreshPendingLookupStatus()
            refreshCallerDiagnostics()
        }
    }

    private fun refreshPendingLookupStatus() {
        if (!::binding.isInitialized || !::repository.isInitialized) return
        lifecycleScope.launch {
            val count = repository.pendingLookupCount()
            pendingLookupCountForStatus = count
            binding.tvPendingLookupCount.text = getString(
                R.string.pending_caller_lookups_count,
                count
            )
            binding.btnRetryPendingLookups.isEnabled = count > 0
            binding.btnClearPendingLookups.isEnabled = count > 0
            refreshAppStatus()
        }
    }

    private fun showClearPendingLookupsDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_pending_lookups)
            .setMessage(R.string.clear_pending_lookups_confirmation)
            .setPositiveButton(R.string.clear_pending_lookups) { _, _ ->
                lifecycleScope.launch {
                    val cleared = repository.clearPendingLookups()
                    refreshPendingLookupStatus()
                    Toast.makeText(
                        this@MainActivity,
                        if (cleared) R.string.pending_lookups_cleared
                        else R.string.pending_lookups_clear_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showClearSavedCallerInformationDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_saved_caller_information)
            .setMessage(R.string.clear_saved_caller_information_confirmation)
            .setPositiveButton(R.string.clear_saved_caller_information_action) { _, _ ->
                lifecycleScope.launch {
                    val cleared = repository.clearSavedCallerInformation()
                    if (cleared) {
                        latestLookupResult = null
                        binding.resultLayout.visibility = View.GONE
                        loadHistory()
                        refreshPendingLookupStatus()
                        Toast.makeText(
                            this@MainActivity,
                            R.string.saved_caller_information_cleared,
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.saved_caller_information_clear_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setRecentCallEnabled(enabled: Boolean) {
        RecentCallPreferences.getInstance(this).setEnabled(enabled)
        if (::binding.isInitialized) {
            updatingRecentCallSwitch = true
            binding.switchShowPreviousCall.isChecked = enabled
            updatingRecentCallSwitch = false
        }
    }

    private fun updateStatusIndicator(enabled: Boolean) {
        val color = ContextCompat.getColor(this, if (enabled && telegramManager.isReady()) R.color.status_active else R.color.status_inactive)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private inner class SwipeGestureListener : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null) return false
            val diffY = e2.y - e1.y
            val diffX = e2.x - e1.x
            if (abs(diffX) > abs(diffY) && abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffX > 0) {
                    navigatePrev()
                } else {
                    navigateNext()
                }
                return true
            }
            return false
        }
    }

    private fun navigateNext() {
        val menu = binding.bottomNavigation.menu
        for (i in 0 until menu.size() - 1) {
            if (menu.getItem(i).isChecked) {
                binding.bottomNavigation.selectedItemId = menu.getItem(i + 1).itemId
                break
            }
        }
    }

    private fun navigatePrev() {
        val menu = binding.bottomNavigation.menu
        for (i in 1 until menu.size()) {
            if (menu.getItem(i).isChecked) {
                binding.bottomNavigation.selectedItemId = menu.getItem(i - 1).itemId
                break
            }
        }
    }
}
