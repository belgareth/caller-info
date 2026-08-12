package com.rakibulcodes.callerinfo

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Reusable call-end monitor. API 31+ uses TelephonyCallback; older supported
 * devices retain the legacy PhoneStateListener path.
 */
class CallStateEndMonitor(
    context: Context,
    private val onCallEnded: () -> Unit
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var observedActiveCall = false
    private var callback31: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null

    fun start(): Boolean {
        val telephonyManager = manager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleState(state)
                }
                callback31 = callback
                telephonyManager.registerTelephonyCallback(appContext.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleState(state)
                }
                legacyListener = listener
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun handleState(state: Int) {
        if (state != TelephonyManager.CALL_STATE_IDLE) {
            observedActiveCall = true
            return
        }
        if (observedActiveCall) {
            observedActiveCall = false
            onCallEnded()
        }
    }

    fun stop() {
        val telephonyManager = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callback31?.let { runCatching { telephonyManager.unregisterTelephonyCallback(it) } }
            callback31 = null
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let { runCatching { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) } }
            legacyListener = null
        }
    }
}
