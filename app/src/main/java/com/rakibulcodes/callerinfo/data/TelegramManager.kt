package com.rakibulcodes.callerinfo.data

import android.content.Context
import com.rakibulcodes.callerinfo.OfflineLookupScheduler
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class TelegramManager private constructor(private val context: Context) {

    @Volatile
    private var client: Client? = null

    @Volatile
    private var nativeAvailable = false

    @Volatile
    private var databaseKeyReady = false

    private val readiness = TelegramReadinessTracker()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configurationMutex = Mutex()
    private val configurationRunning = AtomicBoolean(false)
    private val secureStorage = SecureTelegramStorage(context)
    private val _authState = MutableSharedFlow<TdApi.AuthorizationState>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val authState = _authState.asSharedFlow()

    private val lookupUpdates = BoundedRemoteUpdateBuffer<TdApi.Object>()

    private val prefs =
        context.getSharedPreferences(SecureTelegramStorage.PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val commandTransport = TdlibCommandTransport(
        gatewaySnapshot = {
            client?.let { currentClient ->
                TdlibClientGateway { query, callback ->
                    currentClient.send(query, callback)
                }
            }
        },
        nativeAvailable = { nativeAvailable }
    )

    init {
        nativeAvailable = try {
            System.loadLibrary("tdjni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        readiness.updateNativeAvailable(nativeAvailable)
        if (nativeAvailable) setupClient()
    }

    private fun setupClient() {
        readiness.updateClientAvailable(false)
        databaseKeyReady = false
        try {
            client = Client.create(ResultHandler(), null, null)
            readiness.updateClientAvailable(true)
        } catch (_: UnsatisfiedLinkError) {
            nativeAvailable = false
            client = null
            readiness.updateNativeAvailable(false)
        } catch (_: Exception) {
            client = null
            readiness.updateClientAvailable(false)
        }
    }

    fun isNativeAvailable(): Boolean = nativeAvailable

    fun send(
        query: TdApi.Function<out TdApi.Object>,
        callback: (TdApi.Object) -> Unit
    ) {
        val currentClient = client
        if (!nativeAvailable || currentClient == null) {
            callback(TdApi.Error(503, "Remote transport unavailable"))
            return
        }
        try {
            currentClient.send(query, callback)
        } catch (_: Exception) {
            callback(TdApi.Error(503, "Remote transport unavailable"))
        } catch (_: LinkageError) {
            callback(TdApi.Error(503, "Remote transport unavailable"))
        }
    }

    suspend fun sendSuspend(query: TdApi.Function<out TdApi.Object>): TdApi.Object =
        commandTransport.execute(query)

    inner class ResultHandler : Client.ResultHandler {
        override fun onResult(`object`: TdApi.Object) {
            when (`object`) {
                is TdApi.UpdateNewMessage,
                is TdApi.UpdateMessageContent,
                is TdApi.UpdateMessageSendSucceeded,
                is TdApi.UpdateMessageSendFailed -> lookupUpdates.offer(`object`)
                is TdApi.UpdateAuthorizationState ->
                    handleAuthState(`object`.authorizationState)
            }
        }
    }

    fun saveCredentials(credentials: TelegramCredentials): Boolean =
        secureStorage.saveCredentials(credentials)

    fun savedCredentials(): TelegramCredentials? = secureStorage.readCredentials()

    fun clearCredentials(): Boolean = secureStorage.clearCredentials()

    suspend fun nextLookupUpdate(): TdApi.Object = lookupUpdates.receive()

    fun sendTdlibParameters(apiId: Int, apiHash: String) {
        configureTdlib(apiId, apiHash)
    }

    private fun configureTdlib(apiId: Int, apiHash: String) {
        if (!configurationRunning.compareAndSet(false, true)) return
        managerScope.launch {
            try {
                RemoteBotTransactionCoordinator.run {
                    configurationMutex.withLock {
                        configureTdlibLocked(apiId, apiHash)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                databaseKeyReady = false
            } finally {
                configurationRunning.set(false)
            }
        }
    }

    private suspend fun configureTdlibLocked(apiId: Int, apiHash: String) {
        val databaseDirectory = File(context.filesDir, "tdlib/db")
        val filesDirectory = File(context.filesDir, "tdlib/files")
        val hasExistingDatabase =
            databaseDirectory.isDirectory &&
                databaseDirectory.list()?.isNotEmpty() == true
        val keyPlan = secureStorage.prepareDatabaseKey(hasExistingDatabase)
        activateDatabaseKeyPlan(
            plan = keyPlan,
            openWithKey = { key ->
                sendParameters(apiId, apiHash, databaseDirectory, filesDirectory, key)
            },
            changeKey = { newKey ->
                sendSuspend(TdApi.SetDatabaseEncryptionKey(newKey))
            },
            markActive = secureStorage::markDatabaseKeyActive
        )
        databaseKeyReady = when (keyPlan) {
            is DatabaseKeyPlan.Active -> true
            is DatabaseKeyPlan.MigrateFromEmpty ->
                !secureStorage.databaseMigrationIsPending()
            DatabaseKeyPlan.Unavailable -> false
        }
        if (!databaseKeyReady) {
            throw TdlibUnavailableException()
        }
        performInitialSetup()
        schedulePendingAfterAuthorization()
    }

    private suspend fun sendParameters(
        apiId: Int,
        apiHash: String,
        databaseDirectory: File,
        filesDirectory: File,
        databaseKey: ByteArray
    ) {
        sendSuspend(
            TdApi.SetTdlibParameters(
                false,
                databaseDirectory.absolutePath,
                filesDirectory.absolutePath,
                databaseKey,
                true,
                true,
                true,
                false,
                apiId,
                apiHash,
                "en",
                android.os.Build.MODEL,
                android.os.Build.VERSION.RELEASE,
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "unknown"
            )
        )
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        readiness.updateAuthorizationState(state)
        _authState.tryEmit(state)

        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                prefs.edit().putBoolean("is_logged_in", false).apply()
                val credentials = secureStorage.readCredentials()
                if (credentials != null && credentials.apiId != 0 && credentials.apiHash.isNotEmpty()) {
                    configureTdlib(credentials.apiId, credentials.apiHash)
                }
            }
            is TdApi.AuthorizationStateReady -> {
                prefs.edit().putBoolean("is_logged_in", true).apply()
                if (databaseKeyReady) {
                    performInitialSetup()
                    managerScope.launch {
                        schedulePendingAfterAuthorization()
                    }
                }
            }
            is TdApi.AuthorizationStateClosed -> {
                prefs.edit().putBoolean("is_logged_in", false).apply()
                client = null
                databaseKeyReady = false
                readiness.updateClientAvailable(false)
                setupClient()
            }
            else -> {
                prefs.edit().putBoolean("is_logged_in", false).apply()
            }
        }
    }

    fun reconnect() {
        val currentClient = client ?: return
        if (!nativeAvailable) return
        try {
            currentClient.send(TdApi.SetNetworkType(null)) { }
        } catch (_: Exception) {
            // A later connectivity lifecycle event can retry.
        }
    }

    fun isReady(): Boolean = readiness.isReady() && databaseKeyReady

    suspend fun prepareLookupChat(): TdApi.Chat {
        if (!isReady()) throw AuthenticationNotReadyException()
        if (!prefs.getBoolean("initial_setup_done", false)) {
            performRequiredSetup()
        }
        return sendSuspend(TdApi.SearchPublicChat(LOOKUP_BOT)) as? TdApi.Chat
            ?: throw TdlibCommandFailureException(0)
    }

    private suspend fun performRequiredSetup() {
        val groupSearch = sendSuspend(TdApi.SearchPublicChat(REQUIRED_CHAT))
        if (groupSearch is TdApi.Chat) {
            sendSuspend(TdApi.JoinChat(groupSearch.id))
            sendSuspend(
                TdApi.SetChatNotificationSettings(
                    groupSearch.id,
                    mutedChatSettings()
                )
            )
        }

        val botSearch = sendSuspend(TdApi.SearchPublicChat(LOOKUP_BOT))
        if (botSearch is TdApi.Chat) {
            val chatType = botSearch.type
            if (chatType is TdApi.ChatTypePrivate) {
                val botUserId = chatType.userId
                sendSuspend(
                    TdApi.SetMessageSenderBlockList(
                        TdApi.MessageSenderUser(botUserId),
                        null
                    )
                )
                sendSuspend(
                    TdApi.SendBotStartMessage(
                        botUserId,
                        botSearch.id,
                        ""
                    )
                )
            } else {
                sendSuspend(TdApi.JoinChat(botSearch.id))
            }
            sendSuspend(
                TdApi.SetChatNotificationSettings(
                    botSearch.id,
                    mutedChatSettings()
                )
            )
        }
        prefs.edit().putBoolean("initial_setup_done", true).apply()
    }

    fun performInitialSetup() {
        if (!isReady() || prefs.getBoolean("initial_setup_done", false)) return
        managerScope.launch {
            try {
                RemoteBotTransactionCoordinator.run {
                    if (!prefs.getBoolean("initial_setup_done", false)) {
                        performRequiredSetup()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A later ready-state lifecycle event can retry setup.
            }
        }
    }

    private suspend fun schedulePendingAfterAuthorization() {
        try {
            OfflineLookupScheduler.enqueueAuthorizationReady(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // App resume and later ready-state events provide another bounded opportunity.
        }
    }

    private fun mutedChatSettings() = TdApi.ChatNotificationSettings(
        false,
        Int.MAX_VALUE,
        false,
        0L,
        false,
        false,
        false,
        true,
        false,
        0L,
        false,
        false,
        false,
        true,
        false,
        true
    )

    companion object {
        private const val REQUIRED_CHAT = "true_caller"
        private const val LOOKUP_BOT = "TrueCalleRobot"

        @Volatile
        private var INSTANCE: TelegramManager? = null

        fun getInstance(context: Context): TelegramManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TelegramManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
    }
}

class AuthenticationNotReadyException : Exception("Remote lookup is not ready")
