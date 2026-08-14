package com.rakibulcodes.callerinfo

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.view.*
import android.content.res.ColorStateList
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TimeZone
import kotlin.math.sqrt

class CallerOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var recentCallJob: Job? = null
    private var previewDismissJob: Job? = null
    private var presentationId = 0L
    private var activeCallGeneration: Long? = null
    private var activeNormalizedNumber: String? = null
    private var activeLockedPresentation = false
    private var pendingVerificationState = NumberVerificationState.UNAVAILABLE
    private var pendingLookupSource: CallerLookupSource? = null
    private val presentationState = OverlayPresentationState()
    private var callEndMonitor: CallStateEndMonitor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_PREVIEW -> {
                showCallerCardPreview()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_PREVIEW -> {
                if (presentationState.mode == OverlayPresentationMode.PREVIEW) {
                    removeOverlay()
                } else if (presentationState.mode == OverlayPresentationMode.NONE) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_CLEAR_INCOMING_PRESENTATION -> {
                val generation = intent.getLongExtra("call_generation", -1L)
                if (
                    presentationState.canClearIncoming(generation) &&
                    activeCallGeneration == generation
                ) {
                    removeOverlay()
                }
                return START_NOT_STICKY
            }
        }

        val number = intent?.getStringExtra("number") ?: return START_NOT_STICKY
        val callGeneration = intent.getLongExtra("call_generation", -1L)
        if (
            callGeneration < 0 ||
            !activeIncomingCallGeneration.isCurrent(callGeneration, number)
        ) {
            return START_NOT_STICKY
        }
        val name = intent.getStringExtra("name")
        val carrier = intent.getStringExtra("carrier")
        val country = intent.getStringExtra("country")
        val location = intent.getStringExtra("location")
        val email = intent.getStringExtra("email")
        val userNote = intent.getStringExtra("user_note")
        val error = intent.getStringExtra("error")
        val isLockedPresentation = intent.getBooleanExtra("locked_presentation", false)
        val incomingCallStartMillis =
            intent.getLongExtra("incoming_call_start", System.currentTimeMillis())
        val phoneAccountLabel = intent.getStringExtra("phone_account_label")
        val verificationState = intent.getStringExtra("verification_state")
            .toEnumOrNull<NumberVerificationState>()
            ?: NumberVerificationState.UNAVAILABLE
        val lookupSource = intent.getStringExtra("lookup_source")
            .toEnumOrNull<CallerLookupSource>()
        val lookupStage = intent.getStringExtra("lookup_stage")
            .toEnumOrNull<IncomingLookupStage>()
            ?: incomingLookupStage(
                hasUsefulCallerInformation = hasDisplayableCallerInformation(
                    name = name,
                    carrier = carrier,
                    email = email,
                    hasError = error != null
                ),
                retryScheduled = false,
                errorMessage = error
            )

        val replacingSameCall =
            activeCallGeneration == callGeneration && activeNormalizedNumber == number
        if (replacingSameCall) {
            prepareForOverlayReplacement()
        } else {
            removeOverlayInternal(invalidateActiveCall = false)
        }
        activeCallGeneration = callGeneration
        activeNormalizedNumber = number
        activeLockedPresentation = isLockedPresentation
        pendingVerificationState = verificationState
        pendingLookupSource = if (isLockedPresentation) null else lookupSource
        presentationState.beginRealCall(callGeneration)
        val overlayShown = showOverlay(
            number = number,
            name = name,
            carrier = carrier,
            country = country,
            location = location,
            email = email,
            userNote = userNote,
            error = error,
            phoneAccountLabel = phoneAccountLabel,
            lookupStage = lookupStage,
            isPreview = false,
            isLockedPresentation = isLockedPresentation
        )
        if (overlayShown) {
            loadRecentCall(
                number,
                incomingCallStartMillis,
                presentationId,
                callGeneration
            )
        }
        monitorCallEnd(applicationContext)
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay(
        number: String,
        name: String?,
        carrier: String?,
        country: String?,
        location: String?,
        email: String? = null,
        userNote: String? = null,
        error: String? = null,
        phoneAccountLabel: String? = null,
        lookupStage: IncomingLookupStage,
        isPreview: Boolean,
        isLockedPresentation: Boolean = false
    ): Boolean {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val prefs = getSharedPreferences("Settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val isNightMode = when (themeMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
        val nightConfig = Configuration(resources.configuration)
        nightConfig.uiMode = (nightConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isNightMode) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val configContext = applicationContext.createConfigurationContext(nightConfig)
        val themeContext = ContextThemeWrapper(configContext, R.style.Theme_CallerInfo)
        val inflater = LayoutInflater.from(themeContext)
        val previousView = overlayView

        try {
            val candidateView = inflater.inflate(R.layout.layout_overlay_card, null)

            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val displayPrefs = Test15Preferences.getInstance(applicationContext)
            val cardSize = if (isLockedPresentation) {
                CallerCardSize.COMPACT
            } else {
                displayPrefs.callerCardSize()
            }
            val cardPosition = if (isLockedPresentation) {
                CallerCardPosition.UPPER
            } else {
                displayPrefs.callerCardPosition()
            }
            
            val maxWidthPx = ((if (cardSize == CallerCardSize.COMPACT) 360 else 420) * displayMetrics.density).toInt()
            val preferredWidth = (screenWidth * if (cardSize == CallerCardSize.COMPACT) 0.88 else 0.95).toInt()
            val finalWidth = if (preferredWidth > maxWidthPx) maxWidthPx else preferredWidth

            params = WindowManager.LayoutParams(
                finalWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            
            params?.gravity = if (isLockedPresentation) {
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            } else {
                Gravity.CENTER
            }
            params?.y = if (isLockedPresentation) {
                (24 * displayMetrics.density).toInt()
            } else when (cardPosition) {
                CallerCardPosition.UPPER -> -(screenHeight * if (isLandscape) 0.15f else 0.30f).toInt()
                CallerCardPosition.CENTER -> if (isLandscape) {
                    (screenHeight * 0.15f).toInt()
                } else {
                    -(screenHeight * 0.15f).toInt()
                }
                CallerCardPosition.LOWER -> (screenHeight * if (isLandscape) 0.18f else 0.25f).toInt()
            }

            candidateView.let { view ->
                val hasCallerInformation = hasDisplayableCallerInformation(
                    name = name,
                    carrier = carrier,
                    email = email,
                    hasError = error != null
                )
                val statusIndicator = view.findViewById<View>(R.id.statusIndicator)
                val statusColor = when (lookupStage) {
                    IncomingLookupStage.RESOLVED -> "#4CAF50"
                    IncomingLookupStage.FAILED -> "#F44336"
                    IncomingLookupStage.LOOKING_UP,
                    IncomingLookupStage.RETRY_QUEUED -> "#FFC107"
                }
                statusIndicator.backgroundTintList =
                    ColorStateList.valueOf(android.graphics.Color.parseColor(statusColor))

                view.findViewById<TextView>(R.id.tvName).text = when {
                    lookupStage == IncomingLookupStage.LOOKING_UP ->
                        getString(R.string.looking_up_caller)
                    !name.isNullOrBlank() -> name
                    else -> getString(R.string.caller_unknown)
                }
                view.findViewById<TextView>(R.id.tvNumber).text = number
                val statusView = view.findViewById<TextView>(R.id.tvLookupStatus)
                val statusText = when (lookupStage) {
                    IncomingLookupStage.LOOKING_UP -> null
                    IncomingLookupStage.RESOLVED -> null
                    IncomingLookupStage.RETRY_QUEUED -> listOfNotNull(
                        error?.takeIf(String::isNotBlank),
                        getString(R.string.lookup_will_retry_later)
                    ).joinToString("\n")
                    IncomingLookupStage.FAILED -> error
                }
                statusView.text = statusText
                statusView.visibility = if (statusText.isNullOrBlank()) View.GONE else View.VISIBLE
                view.findViewById<View>(R.id.overlayActions).visibility =
                    if (!isLockedPresentation &&
                        incomingActionsVisible(lookupStage, hasCallerInformation, isPreview)
                    ) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                bindVerificationBadge(view, pendingVerificationState)
                if (isLockedPresentation) {
                    view.findViewById<LinearLayout>(R.id.rowLookupSource).visibility = View.GONE
                } else {
                    bindLookupSource(
                        view = view,
                        source = pendingLookupSource,
                        hasCallerInformation = hasCallerInformation
                    )
                }

                val carrierText = listOfNotNull(carrier, country).joinToString(" · ")
                val carrierRow = view.findViewById<LinearLayout>(R.id.rowCarrier)
                if (carrierText.isNotEmpty()) {
                    view.findViewById<TextView>(R.id.tvCarrier).text = carrierText
                    carrierRow.visibility = View.VISIBLE
                } else {
                    carrierRow.visibility = View.GONE
                }
                
                val rowPhoneAccount = view.findViewById<LinearLayout>(R.id.rowPhoneAccount)
                val tvPhoneAccount = view.findViewById<TextView>(R.id.tvPhoneAccount)
                if (!isLockedPresentation && !phoneAccountLabel.isNullOrBlank()) {
                    tvPhoneAccount.text = phoneAccountLabel
                    rowPhoneAccount.visibility = View.VISIBLE
                } else {
                    rowPhoneAccount.visibility = View.GONE
                }

                val rowUserNote = view.findViewById<LinearLayout>(R.id.rowUserNote)
                val tvUserNote = view.findViewById<TextView>(R.id.tvUserNote)
                if (!isLockedPresentation && !userNote.isNullOrBlank() && cardSize == CallerCardSize.EXPANDED) {
                    tvUserNote.text = userNote
                    rowUserNote.visibility = View.VISIBLE
                } else {
                    rowUserNote.visibility = View.GONE
                }

                val rowEmail = view.findViewById<LinearLayout>(R.id.rowEmail)
                val tvEmail = view.findViewById<TextView>(R.id.tvEmail)
                if (!isLockedPresentation && !email.isNullOrEmpty() && cardSize == CallerCardSize.EXPANDED) {
                    tvEmail.text = email
                    rowEmail.visibility = View.VISIBLE
                } else {
                    rowEmail.visibility = View.GONE
                }

                val rowLocation = view.findViewById<LinearLayout>(R.id.rowLocation)
                val tvLocation = view.findViewById<TextView>(R.id.tvLocation)
                if (!isLockedPresentation && !location.isNullOrEmpty() && cardSize == CallerCardSize.EXPANDED) {
                    tvLocation.text = location
                    rowLocation.visibility = View.VISIBLE
                } else {
                    rowLocation.visibility = View.GONE
                }

                val resolvedName = name?.takeIf { it.isNotBlank() } ?: "Unknown"
                val shareText = buildOverlayShareText(
                    number = number,
                    name = resolvedName,
                    carrier = carrier,
                    country = country,
                    email = email,
                    location = location
                )

                view.findViewById<View>(R.id.btnOverlayDial).setOnClickListener {
                    runCatching { startActivity(buildDialIntent(number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }

                view.findViewById<View>(R.id.btnOverlaySave).setOnClickListener {
                    val insertIntent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
                        type = ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.NAME, resolvedName)
                        putExtra(ContactsContract.Intents.Insert.PHONE, number)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(insertIntent)
                }

                view.findViewById<View>(R.id.btnOverlayCopy).setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("caller_info_overlay", shareText))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }

                view.findViewById<View>(R.id.btnOverlayShare).setOnClickListener {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share via").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }

                // Handle close button click
                view.findViewById<View>(R.id.btnClose).setOnClickListener {
                    animateDismiss(view, 0f, 300f)
                }

                // Swipe-to-dismiss in any direction
                var initialX = 0f
                var initialY = 0f
                var initialTouchX = 0f
                var initialTouchY = 0f

                view.setOnTouchListener { _, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params?.x?.toFloat() ?: 0f
                            initialY = params?.y?.toFloat() ?: 0f
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            view.animate().cancel()
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val deltaX = event.rawX - initialTouchX
                            val deltaY = event.rawY - initialTouchY
                            
                            params?.x = (initialX + deltaX).toInt()
                            params?.y = (initialY + deltaY).toInt()
                            
                            val distance = sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
                            val alpha = (1f - distance / 600f).coerceIn(0.2f, 1f)
                            val scale = (1f - distance / 2400f).coerceIn(0.85f, 1f)
                            view.alpha = alpha
                            view.scaleX = scale
                            view.scaleY = scale
                            
                            try {
                                windowManager?.updateViewLayout(view, params)
                            } catch (e: Exception) {}
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val deltaX = event.rawX - initialTouchX
                            val deltaY = event.rawY - initialTouchY
                            val distance = sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()
                            val velocity = distance / 0.3f 
                            
                            if (distance > 200 || velocity > 1200) {
                                val angle = Math.atan2(deltaY.toDouble(), deltaX.toDouble())
                                val flingDist = 800f
                                val targetX = (initialX + Math.cos(angle) * flingDist).toFloat()
                                val targetY = (initialY + Math.sin(angle) * flingDist).toFloat()
                                animateDismiss(view, targetX - (params?.x ?: 0), targetY - (params?.y ?: 0))
                            } else {
                                params?.x = initialX.toInt()
                                params?.y = initialY.toInt()
                                try {
                                    windowManager?.updateViewLayout(view, params)
                                } catch (e: Exception) {}
                                view.animate()
                                    .alpha(1f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(300)
                                    .setInterpolator(OvershootInterpolator(2f))
                                    .start()
                            }
                            true
                        }
                        else -> false
                    }
                }

                windowManager?.addView(view, params)
                previousView?.takeIf { it !== view }?.let { oldView ->
                    runCatching { windowManager?.removeView(oldView) }
                }
                overlayView = view
                if (!isPreview) IncomingOverlayFallbackNotification.cancel(applicationContext)
            }
            return true
        } catch (_: Exception) {
            if (!isPreview) {
                IncomingOverlayFallbackNotification.show(
                    context = applicationContext,
                    number = number,
                    stage = lookupStage,
                    error = error,
                    generation = activeCallGeneration ?: -1L
                )
            }
            if (previousView == null) {
                presentationState.clear()
                stopSelf()
            }
            return false
        }
    }

    private fun showCallerCardPreview() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        if (!presentationState.beginPreview()) return

        val nowMillis = System.currentTimeMillis()
        val preview = createCallerCardPreviewData(nowMillis).copy(
            name = getString(R.string.preview_sample_caller),
            detail = getString(R.string.preview_only)
        )
        pendingVerificationState = preview.verificationState
        pendingLookupSource = preview.lookupSource
        showOverlay(
            number = preview.number,
            name = preview.name,
            carrier = preview.detail,
            country = null,
            location = null,
            email = null,
            userNote = null,
            error = null,
            phoneAccountLabel = null,
            lookupStage = IncomingLookupStage.RESOLVED,
            isPreview = true
        )
        overlayView?.let { view ->
            bindRecentCall(view, preview.recentCall)
        }
        previewDismissJob = serviceScope.launch {
            delay(PREVIEW_DURATION_MILLIS)
            if (presentationState.shouldAutoDismissPreview()) {
                removeOverlay()
            }
        }
    }

    private fun animateDismiss(view: View, translationX: Float, translationY: Float) {
        view.animate()
            .translationXBy(translationX)
            .translationYBy(translationY)
            .alpha(0f)
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(280)
            .setInterpolator(AccelerateInterpolator(1.5f))
            .withEndAction { 
                view.visibility = View.GONE
                removeOverlay() 
            }
            .start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayView?.let { view ->
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            
            val displayPrefs = Test15Preferences.getInstance(applicationContext)
            val cardSize = if (activeLockedPresentation) {
                CallerCardSize.COMPACT
            } else {
                displayPrefs.callerCardSize()
            }
            val cardPosition = if (activeLockedPresentation) {
                CallerCardPosition.UPPER
            } else {
                displayPrefs.callerCardPosition()
            }
            val maxWidthPx = ((if (cardSize == CallerCardSize.COMPACT) 360 else 420) * displayMetrics.density).toInt()
            val preferredWidth = (screenWidth * if (cardSize == CallerCardSize.COMPACT) 0.88 else 0.95).toInt()
            params?.width = if (preferredWidth > maxWidthPx) maxWidthPx else preferredWidth
            params?.gravity = if (activeLockedPresentation) {
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            } else {
                Gravity.CENTER
            }
            params?.y = if (activeLockedPresentation) {
                (24 * displayMetrics.density).toInt()
            } else when (cardPosition) {
                CallerCardPosition.UPPER -> -(screenHeight * if (isLandscape) 0.15f else 0.30f).toInt()
                CallerCardPosition.CENTER -> if (isLandscape) {
                    (screenHeight * 0.15f).toInt()
                } else {
                    -(screenHeight * 0.15f).toInt()
                }
                CallerCardPosition.LOWER -> (screenHeight * if (isLandscape) 0.18f else 0.25f).toInt()
            }

            try {
                windowManager?.updateViewLayout(view, params)
            } catch (_: Exception) {
                // A later presentation update can retry with the current generation.
            }
        }
    }

    private fun prepareForOverlayReplacement() {
        recentCallJob?.cancel()
        recentCallJob = null
        previewDismissJob?.cancel()
        previewDismissJob = null
        stopMonitoringCallEnd()
        presentationId++
    }

    private fun removeOverlayInternal(invalidateActiveCall: Boolean = true) {
        recentCallJob?.cancel()
        recentCallJob = null
        previewDismissJob?.cancel()
        previewDismissJob = null
        stopMonitoringCallEnd()
        presentationId++
        val generationBeingCleared = activeCallGeneration
        if (invalidateActiveCall) {
            generationBeingCleared?.let(activeIncomingCallGeneration::invalidate)
        }
        generationBeingCleared?.let(LockedCallerCardController::onPresentationCleared)
        activeCallGeneration = null
        activeNormalizedNumber = null
        activeLockedPresentation = false
        IncomingOverlayFallbackNotification.cancel(applicationContext)
        pendingVerificationState = NumberVerificationState.UNAVAILABLE
        pendingLookupSource = null
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {}
            overlayView = null
        }
        presentationState.clear()
    }

    private fun removeOverlay() {
        removeOverlayInternal()
        stopSelf()
    }

    private fun monitorCallEnd(context: Context) {
        if (callEndMonitor != null) return
        callEndMonitor = CallStateEndMonitor(context) {
            val generation = activeCallGeneration
            if (generation != null) {
                removeOverlay()
                activeIncomingCallGeneration.invalidate(generation)
            }
        }.also { monitor ->
            if (!monitor.start()) callEndMonitor = null
        }
    }

    private fun stopMonitoringCallEnd() {
        callEndMonitor?.stop()
        callEndMonitor = null
    }

    private fun loadRecentCall(
        number: String,
        cutoffMillis: Long,
        expectedPresentationId: Long,
        expectedGeneration: Long
    ) {
        val view = overlayView ?: return
        val row = view.findViewById<LinearLayout>(R.id.rowRecentCall)
        row.visibility = View.GONE

        val config = NumberFormattingPreferences.getInstance(applicationContext).getConfig()
        recentCallJob = serviceScope.launch {
            val interaction = RecentCallRepository.getInstance(applicationContext)
                .findPreviousCall(number, cutoffMillis, config)
                ?: return@launch

            if (
                !isRecentCallPresentationCurrent(
                    expectedPresentationId = expectedPresentationId,
                    currentPresentationId = presentationId,
                    sameOverlayView = overlayView === view
                ) ||
                !activeIncomingCallGeneration.isCurrent(expectedGeneration, number) ||
                activeCallGeneration != expectedGeneration ||
                activeNormalizedNumber != number
            ) {
                return@launch
            }

            bindRecentCall(view, interaction)
        }
    }

    private fun bindRecentCall(view: View, interaction: RecentCallInteraction) {
        val icon = view.findViewById<ImageView>(R.id.ivRecentCallType)
        val text = view.findViewById<TextView>(R.id.tvRecentCallTime)
        icon.setImageResource(R.drawable.ic_history)
        icon.contentDescription = getString(R.string.last_seen)
        text.text = formatLastSeenPresentation(
            label = getString(R.string.last_seen),
            formattedTimestamp = formatRecentCallTime(interaction.timestampMillis)
        )
        view.findViewById<LinearLayout>(R.id.rowRecentCall).visibility = View.VISIBLE
    }

    private fun bindVerificationBadge(view: View, state: NumberVerificationState) {
        val row = view.findViewById<LinearLayout>(R.id.rowNumberVerification)
        val icon = view.findViewById<ImageView>(R.id.ivNumberVerification)
        val text = view.findViewById<TextView>(R.id.tvNumberVerification)
        when (visibleNumberVerificationState(state, presentationState.mode == OverlayPresentationMode.REAL_CALL || presentationState.mode == OverlayPresentationMode.PREVIEW)) {
            NumberVerificationState.PASSED -> {
                icon.setImageResource(R.drawable.ic_number_verified)
                icon.imageTintList = ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        icon,
                        com.google.android.material.R.attr.colorPrimary
                    )
                )
                icon.contentDescription = getString(R.string.number_verification_passed_description)
                text.setText(R.string.number_verified)
                row.visibility = View.VISIBLE
            }
            NumberVerificationState.FAILED -> {
                icon.setImageResource(R.drawable.ic_number_verification_failed)
                icon.imageTintList = ColorStateList.valueOf(
                    com.google.android.material.color.MaterialColors.getColor(
                        icon,
                        com.google.android.material.R.attr.colorError
                    )
                )
                icon.contentDescription = getString(R.string.number_verification_failed_description)
                text.setText(R.string.number_verification_failed)
                row.visibility = View.VISIBLE
            }
            NumberVerificationState.UNAVAILABLE,
            null -> row.visibility = View.GONE
        }
    }

    private fun bindLookupSource(
        view: View,
        source: CallerLookupSource?,
        hasCallerInformation: Boolean
    ) {
        val row = view.findViewById<LinearLayout>(R.id.rowLookupSource)
        val visibleSource = visibleLookupSource(
            source = source,
            settingEnabled = LookupSourcePreferences.getInstance(applicationContext).isEnabled(),
            hasCallerInformation = hasCallerInformation
        )
        if (visibleSource == null) {
            row.visibility = View.GONE
            return
        }

        view.findViewById<TextView>(R.id.tvLookupSource).setText(
            when (visibleSource) {
                CallerLookupSource.CONTACT -> R.string.lookup_source_contact
                CallerLookupSource.LOCAL -> R.string.lookup_source_local
                CallerLookupSource.REMOTE -> R.string.lookup_source_remote
            }
        )
        row.visibility = View.VISIBLE
    }

    private fun formatRecentCallTime(timestampMillis: Long): String {
        val locale = Locale.getDefault()
        val timeZone = TimeZone.getDefault()
        val use24HourTime = android.text.format.DateFormat.is24HourFormat(this)

        return RecentCallDateFormatter.format(
            timestampMillis = timestampMillis,
            nowMillis = System.currentTimeMillis(),
            locale = locale,
            timeZone = timeZone,
            yesterdayLabel = getString(R.string.recent_call_yesterday)
        ) { style ->
            val skeleton = when (style) {
                RecentCallDateStyle.TODAY,
                RecentCallDateStyle.YESTERDAY -> if (use24HourTime) "Hm" else "hm"
                RecentCallDateStyle.CURRENT_YEAR ->
                    if (use24HourTime) "MMMdHm" else "MMMdhm"
                RecentCallDateStyle.PREVIOUS_YEAR -> "yMMMd"
            }
            android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        }
    }

    private fun buildOverlayShareText(
        number: String,
        name: String,
        carrier: String?,
        country: String?,
        email: String?,
        location: String?
    ): String {
        val sb = StringBuilder()
        sb.append("Name: ").append(name)
        sb.append("\nNumber: ").append(number)

        val carrierText = listOfNotNull(carrier, country).joinToString(", ")
        if (carrierText.isNotEmpty()) sb.append("\n").append(carrierText)
        if (!email.isNullOrEmpty()) sb.append("\nEmail: ").append(email)
        if (!location.isNullOrEmpty()) sb.append("\nLocation: ").append(location)

        return sb.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlayInternal()
        serviceScope.cancel()
    }

    companion object {
        private const val ACTION_SHOW_PREVIEW =
            "com.rakibulcodes.callerinfo.action.SHOW_CALLER_CARD_PREVIEW"
        private const val ACTION_DISMISS_PREVIEW =
            "com.rakibulcodes.callerinfo.action.DISMISS_CALLER_CARD_PREVIEW"
        private const val ACTION_CLEAR_INCOMING_PRESENTATION =
            "com.rakibulcodes.callerinfo.action.CLEAR_INCOMING_PRESENTATION"
        private const val PREVIEW_DURATION_MILLIS = 10_000L

        fun showPreview(context: Context) {
            context.startService(
                Intent(context, CallerOverlayService::class.java).setAction(ACTION_SHOW_PREVIEW)
            )
        }

        fun dismissPreview(context: Context) {
            try {
                context.startService(
                    Intent(context, CallerOverlayService::class.java)
                        .setAction(ACTION_DISMISS_PREVIEW)
                )
            } catch (_: IllegalStateException) {
                // The preview also expires automatically if the app can no longer start services.
            }
        }

        fun clearIncomingPresentation(context: Context, generation: Long) {
            try {
                context.startService(
                    Intent(context, CallerOverlayService::class.java)
                        .setAction(ACTION_CLEAR_INCOMING_PRESENTATION)
                        .putExtra("call_generation", generation)
                )
            } catch (_: IllegalStateException) {
                // A stale card cannot be updated because the generation is already invalid.
            }
        }
    }
}

private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } }
