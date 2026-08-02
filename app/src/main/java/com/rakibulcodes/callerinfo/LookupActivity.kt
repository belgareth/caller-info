package com.rakibulcodes.callerinfo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rakibulcodes.callerinfo.data.CallerInfoRepository
import com.rakibulcodes.callerinfo.data.ExternalLookupConfirmationState
import com.rakibulcodes.callerinfo.data.ExternalLookupValidation
import com.rakibulcodes.callerinfo.data.validateExternalLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LookupActivity : AppCompatActivity() {
    private val confirmationState = ExternalLookupConfirmationState()
    private var lookupJob: Job? = null
    private var confirmationDialog: AlertDialog? = null
    private var activeGeneration: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        cancelActiveRequest()
        val externalText = when {
            intent.action == Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.action == Intent.ACTION_PROCESS_TEXT &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        val config = NumberFormattingPreferences.getInstance(applicationContext).getConfig()
        val validation = validateExternalLookup(
            action = intent.action,
            mimeType = intent.type,
            uriScheme = intent.data?.scheme,
            input = externalText,
            normalize = { input -> normalizePhoneNumber(input, config) }
        )
        if (validation !is ExternalLookupValidation.Valid) {
            Toast.makeText(this, R.string.external_lookup_invalid, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val generation = confirmationState.begin()
        activeGeneration = generation
        confirmationDialog = AlertDialog.Builder(this)
            .setTitle(R.string.external_lookup_confirmation)
            .setNegativeButton(R.string.external_lookup_cancel) { _, _ ->
                confirmationState.cancel(generation)
                activeGeneration = null
                finish()
            }
            .setPositiveButton(R.string.external_lookup_confirm) { _, _ ->
                if (confirmationState.confirm(generation)) {
                    startConfirmedLookup(generation, validation.normalizedNumber)
                }
            }
            .setOnCancelListener {
                confirmationState.cancel(generation)
                activeGeneration = null
                finish()
            }
            .create()
            .also(AlertDialog::show)
    }

    private fun startConfirmedLookup(generation: Long, normalizedNumber: String) {
        if (!confirmationState.canLookup(generation)) return
        lookupJob = lifecycleScope.launch {
            try {
                val result = CallerInfoRepository.getInstance(applicationContext)
                    .getCallerInfoWithSource(
                        rawNumber = normalizedNumber,
                        requestStillValid = { confirmationState.canLookup(generation) }
                    )
                if (!confirmationState.canLookup(generation)) return@launch
                NotificationHelper.showNotification(
                    context = this@LookupActivity,
                    title = result.callerInfo.number,
                    message = NotificationHelper.buildNotificationMessage(result.callerInfo),
                    result = result.callerInfo
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (confirmationState.canLookup(generation)) {
                    Toast.makeText(
                        this@LookupActivity,
                        "Remote lookup is temporarily unavailable",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                if (confirmationState.canLookup(generation)) {
                    confirmationState.cancel(generation)
                    activeGeneration = null
                    finish()
                }
            }
        }
    }

    private fun cancelActiveRequest() {
        lookupJob?.cancel()
        lookupJob = null
        confirmationDialog?.dismiss()
        confirmationDialog = null
        activeGeneration?.let(confirmationState::cancel)
        activeGeneration = null
    }

    override fun onDestroy() {
        cancelActiveRequest()
        super.onDestroy()
    }
}
