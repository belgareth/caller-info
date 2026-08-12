package com.rakibulcodes.callerinfo

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rakibulcodes.callerinfo.data.CallerPresentationInput
import com.rakibulcodes.callerinfo.data.CallerPresentationMode
import com.rakibulcodes.callerinfo.data.callerPresentation
import com.rakibulcodes.callerinfo.databinding.ActivityLockedCallerCardBinding
import java.lang.ref.WeakReference

class LockedCallerCardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLockedCallerCardBinding
    private var generation: Long = INVALID_GENERATION
    private var normalizedNumber: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        binding = ActivityLockedCallerCardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        activeActivity = WeakReference(this)
        binding.dismissLockedCallerCard.setOnClickListener { finish() }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val requestedGeneration = intent.getLongExtra(EXTRA_GENERATION, INVALID_GENERATION)
        val requestedNumber = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        if (
            intent.action == ACTION_CLEAR &&
            requestedGeneration == generation &&
            requestedNumber == normalizedNumber
        ) {
            finish()
            return
        }
        if (
            intent.action != ACTION_SHOW ||
            !activeIncomingCallGeneration.isCurrent(requestedGeneration, requestedNumber)
        ) {
            finish()
            return
        }

        generation = requestedGeneration
        normalizedNumber = requestedNumber
        val presentation = callerPresentation(
            CallerPresentationInput(
                name = intent.getStringExtra(EXTRA_NAME),
                number = requestedNumber,
                email = null,
                address = null,
                location = null,
                lookupSource = null,
                recentCall = null,
                verificationState = intent.getStringExtra(EXTRA_VERIFICATION)
            ),
            CallerPresentationMode.LOCKED_REDACTED
        )
        val lookupStage = intent.getStringExtra(EXTRA_LOOKUP_STAGE)
            ?.let { value -> runCatching { IncomingLookupStage.valueOf(value) }.getOrNull() }
            ?: IncomingLookupStage.LOOKING_UP
        val error = intent.getStringExtra(EXTRA_ERROR)
        binding.lockedCallerName.text = when {
            lookupStage == IncomingLookupStage.LOOKING_UP ->
                getString(R.string.looking_up_caller)
            !presentation.name.isNullOrBlank() -> presentation.name
            else -> getString(R.string.caller_unknown)
        }
        binding.lockedCallerNumber.text = presentation.number
        val statusText = when (lookupStage) {
            IncomingLookupStage.LOOKING_UP,
            IncomingLookupStage.RESOLVED -> null
            IncomingLookupStage.RETRY_QUEUED -> listOfNotNull(
                error?.takeIf(String::isNotBlank),
                getString(R.string.lookup_will_retry_later)
            ).joinToString("\n")
            IncomingLookupStage.FAILED -> error
        }
        binding.lockedLookupStatus.text = statusText
        binding.lockedLookupStatus.visibility =
            if (statusText.isNullOrBlank()) View.GONE else View.VISIBLE
        val verification = presentation.verificationState
            ?.let { value -> runCatching { NumberVerificationState.valueOf(value) }.getOrNull() }
        when (verification) {
            NumberVerificationState.PASSED -> {
                binding.lockedVerificationBadge.visibility = View.VISIBLE
                binding.lockedVerificationBadge.setText(R.string.number_verified)
            }
            NumberVerificationState.FAILED -> {
                binding.lockedVerificationBadge.visibility = View.VISIBLE
                binding.lockedVerificationBadge.setText(R.string.number_verification_failed)
            }
            else -> binding.lockedVerificationBadge.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        if (activeActivity?.get() === this) activeActivity = null
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_SHOW =
            "com.rakibulcodes.callerinfo.action.SHOW_LOCKED_CALLER"
        internal const val ACTION_CLEAR =
            "com.rakibulcodes.callerinfo.action.CLEAR_LOCKED_CALLER"
        internal const val EXTRA_GENERATION = "locked_call_generation"
        internal const val EXTRA_NUMBER = "locked_call_number"
        internal const val EXTRA_NAME = "locked_call_name"
        internal const val EXTRA_VERIFICATION = "locked_call_verification"
        internal const val EXTRA_LOOKUP_STAGE = "locked_call_lookup_stage"
        internal const val EXTRA_ERROR = "locked_call_error"
        private const val INVALID_GENERATION = -1L
        @Volatile
        private var activeActivity: WeakReference<LockedCallerCardActivity>? = null

        internal fun clearActive(generation: Long, normalizedNumber: String) {
            val activity = activeActivity?.get() ?: return
            activity.runOnUiThread {
                if (
                    activity.generation == generation &&
                    activity.normalizedNumber == normalizedNumber
                ) {
                    activity.finish()
                }
            }
        }
    }
}

object LockedCallerCardController {
    private const val CHANNEL_ID = "incoming_caller_card"
    private const val NOTIFICATION_ID = 8102
    private var activeGeneration: Long? = null
    private var activeNumber: String? = null
    private var callEndMonitor: CallStateEndMonitor? = null

    @Synchronized
    fun isActive(generation: Long): Boolean = activeGeneration == generation

    fun present(
        context: Context,
        generation: Long,
        normalizedNumber: String,
        name: String?,
        verificationState: NumberVerificationState,
        lookupStage: IncomingLookupStage,
        error: String? = null
    ) {
        if (!activeIncomingCallGeneration.isCurrent(generation, normalizedNumber)) return
        synchronized(this) {
            activeGeneration = generation
            activeNumber = normalizedNumber
        }
        val intent = Intent(context, LockedCallerCardActivity::class.java).apply {
            action = LockedCallerCardActivity.ACTION_SHOW
            putExtra(LockedCallerCardActivity.EXTRA_GENERATION, generation)
            putExtra(LockedCallerCardActivity.EXTRA_NUMBER, normalizedNumber)
            putExtra(LockedCallerCardActivity.EXTRA_NAME, name)
            putExtra(LockedCallerCardActivity.EXTRA_VERIFICATION, verificationState.name)
            putExtra(LockedCallerCardActivity.EXTRA_LOOKUP_STAGE, lookupStage.name)
            putExtra(LockedCallerCardActivity.EXTRA_ERROR, error)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.incoming_caller_card_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.incoming_caller_card_channel_description)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }
        val publicNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small_icon)
            .setContentTitle(context.getString(R.string.public_caller_notification_title))
            .setContentText(context.getString(R.string.public_caller_notification_text))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val privateNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small_icon)
            .setContentTitle(name ?: context.getString(R.string.caller_unknown))
            .setContentText(normalizedNumber)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setFullScreenIntent(fullScreenIntent, true)
            .setOngoing(true)
            .build()
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            manager.notify(NOTIFICATION_ID, privateNotification)
        }
        runCatching { context.startActivity(intent) }
        monitorCallEnd(context.applicationContext)
    }

    fun clear(context: Context, generation: Long) {
        val number = synchronized(this) {
            if (activeGeneration != generation) return
            val currentNumber = activeNumber ?: return
            activeGeneration = null
            activeNumber = null
            currentNumber
        }
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        LockedCallerCardActivity.clearActive(generation, number)
        stopMonitoringCallEnd()
    }

    fun clearCurrent(context: Context) {
        val generation = synchronized(this) { activeGeneration } ?: return
        clear(context, generation)
    }

    fun isDeviceLocked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    private fun monitorCallEnd(context: Context) {
        synchronized(this) { if (callEndMonitor != null) return }
        val monitor = CallStateEndMonitor(context) {
            val current = synchronized(this@LockedCallerCardController) { activeGeneration }
            if (current != null) {
                clear(context, current)
                activeIncomingCallGeneration.invalidate(current)
            }
        }
        if (monitor.start()) {
            synchronized(this) { callEndMonitor = monitor }
        }
    }

    private fun stopMonitoringCallEnd() {
        val monitor = synchronized(this) {
            val current = callEndMonitor
            callEndMonitor = null
            current
        }
        monitor?.stop()
    }
}
