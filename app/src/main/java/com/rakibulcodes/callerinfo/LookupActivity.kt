package com.rakibulcodes.callerinfo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rakibulcodes.callerinfo.data.CallerInfoRepository
import com.rakibulcodes.callerinfo.data.RequestGenerationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LookupActivity : AppCompatActivity() {

    private lateinit var repository: CallerInfoRepository
    private val requestGeneration = RequestGenerationTracker()
    private var lookupJob: Job? = null
    private var activeGeneration: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CallerInfoRepository.getInstance(applicationContext)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        cancelActiveLookup()
        val sharedText = when {
            intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.action == Intent.ACTION_PROCESS_TEXT && Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }

        val normalizedNumber = repository.sanitizeNumber(sharedText)
        if (normalizedNumber.isEmpty()) {
            Toast.makeText(this, "No valid number found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Toast.makeText(this, "Searching…", Toast.LENGTH_SHORT).show()
        val generation = requestGeneration.begin()
        activeGeneration = generation
        lookupJob = lifecycleScope.launch {
            try {
                val lookupResult = repository.getCallerInfoWithSource(
                    rawNumber = normalizedNumber,
                    requestStillValid = { requestGeneration.isCurrent(generation) }
                )
                if (!requestGeneration.isCurrent(generation)) return@launch

                val result = lookupResult.callerInfo
                val message = NotificationHelper.buildNotificationMessage(result)
                NotificationHelper.showNotification(
                    this@LookupActivity,
                    result.number,
                    message,
                    result = result
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The exported lookup remains silent on a transient failure.
            } finally {
                if (requestGeneration.isCurrent(generation)) {
                    requestGeneration.invalidate(generation)
                    activeGeneration = null
                    finish()
                }
            }
        }
    }

    private fun cancelActiveLookup() {
        lookupJob?.cancel()
        lookupJob = null
        activeGeneration?.let(requestGeneration::invalidate)
        activeGeneration = null
    }

    override fun onDestroy() {
        cancelActiveLookup()
        super.onDestroy()
    }
}
