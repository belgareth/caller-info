package com.rakibulcodes.callerinfo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object IncomingOverlayFallbackNotification {
    private const val CHANNEL_ID = "incoming_overlay_fallback"
    private const val NOTIFICATION_ID = 8103
    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: android.telephony.PhoneStateListener? = null
    @Volatile
    private var activeGeneration: Long? = null

    fun show(
        context: Context,
        number: String,
        stage: IncomingLookupStage,
        error: String?,
        generation: Long
    ) {
        if (!activeIncomingCallGeneration.isCurrent(generation, number)) return
        synchronized(this) {
            activeGeneration = generation
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.incoming_overlay_fallback_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(
                        R.string.incoming_overlay_fallback_channel_description
                    )
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
            )
        }

        val status = when (stage) {
            IncomingLookupStage.LOOKING_UP -> context.getString(R.string.looking_up_caller)
            IncomingLookupStage.RESOLVED ->
                context.getString(R.string.caller_information_available)
            IncomingLookupStage.RETRY_QUEUED -> listOfNotNull(
                error?.takeIf(String::isNotBlank),
                context.getString(R.string.lookup_will_retry_later)
            ).joinToString("\n")
            IncomingLookupStage.FAILED ->
                error?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.caller_unknown)
        }

        val publicNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small_icon)
            .setContentTitle(context.getString(R.string.public_caller_notification_title))
            .setContentText(context.getString(R.string.public_caller_notification_text))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val privateNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_small_icon)
            .setContentTitle(number)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, privateNotification)
        monitorCallEnd(context.applicationContext, generation)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        synchronized(this) {
            activeGeneration = null
        }
        stopMonitoringCallEnd()
    }

    @Suppress("DEPRECATION")
    private fun monitorCallEnd(context: Context, generation: Long) {
        synchronized(this) {
            if (phoneStateListener != null && activeGeneration == generation) return
        }
        stopMonitoringCallEnd()
        val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return
        val listener = object : android.telephony.PhoneStateListener() {
            private var observedActiveCall = false

            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state != TelephonyManager.CALL_STATE_IDLE) {
                    observedActiveCall = true
                    return
                }
                if (!observedActiveCall || activeGeneration != generation) return
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
                stopMonitoringCallEnd()
                activeGeneration = null
                activeIncomingCallGeneration.invalidate(generation)
            }
        }
        try {
            manager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            synchronized(this) {
                telephonyManager = manager
                phoneStateListener = listener
            }
        } catch (_: SecurityException) {
            // The notification remains private and the next generation replaces it.
        }
    }

    @Suppress("DEPRECATION")
    private fun stopMonitoringCallEnd() {
        val (manager, listener) = synchronized(this) {
            val current = telephonyManager to phoneStateListener
            phoneStateListener = null
            telephonyManager = null
            current
        }
        if (listener != null) {
            runCatching {
                manager?.listen(
                    listener,
                    android.telephony.PhoneStateListener.LISTEN_NONE
                )
            }
        }
    }
}
