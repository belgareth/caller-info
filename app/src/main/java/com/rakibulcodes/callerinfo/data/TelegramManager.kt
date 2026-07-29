package com.rakibulcodes.callerinfo.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

class TelegramManager private constructor(private val context: Context) {

    @Volatile
    private var client: Client? = null

    @Volatile
    private var nativeAvailable = false

    private val readiness = TelegramReadinessTracker()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _authState = MutableSharedFlow<TdApi.AuthorizationState>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val authState = _authState.asSharedFlow()

    private val _updates = MutableSharedFlow<TdApi.Object>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val updates = _updates.asSharedFlow()

    private val prefs = context.getSharedPreferences("TelegramSettings", Context.MODE_PRIVATE)
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
        currentClient.send(query, callback)
    }

    suspend fun sendSuspend(query: TdApi.Function<out TdApi.Object>): TdApi.Object =
        commandTransport.execute(query)

    inner class ResultHandler : Client.ResultHandler {
        override fun onResult(`object`: TdApi.Object) {
            _updates.tryEmit(`object`)
            if (`object` is TdApi.UpdateAuthorizationState) {
                handleAuthState(`object`.authorizationState)
            }
        }
    }

    fun sendTdlibParameters(apiId: Int, apiHash: String) {
        val databaseDirectory = context.filesDir.absolutePath + "/tdlib/db"
        val filesDirectory = context.filesDir.absolutePath + "/tdlib/files"
        send(
            TdApi.SetTdlibParameters(
                false,
                databaseDirectory,
                filesDirectory,
                ByteArray(0),
                true,
                true,
                true,
                false,
                apiId,
                apiHash,
                "en",
                android.os.Build.MODEL,
                android.os.Build.VERSION.RELEASE,
                "1.0"
            )
        ) { }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        readiness.updateAuthorizationState(state)
        _authState.tryEmit(state)

        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                prefs.edit().putBoolean("is_logged_in", false).apply()
                val apiId = prefs.getInt("api_id", 0)
                val apiHash = prefs.getString("api_hash", "").orEmpty()
                if (apiId != 0 && apiHash.isNotEmpty()) {
                    sendTdlibParameters(apiId, apiHash)
                }
            }
            is TdApi.AuthorizationStateReady -> {
                prefs.edit().putBoolean("is_logged_in", true).apply()
            }
            is TdApi.AuthorizationStateClosed -> {
                prefs.edit().putBoolean("is_logged_in", false).apply()
                client = null
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
        currentClient.send(TdApi.SetNetworkType(null)) { }
    }

    fun isReady(): Boolean = readiness.isReady()

    suspend fun ensureJoined(username: String, isBot: Boolean = false): TdApi.Chat {
        val chat = sendSuspend(TdApi.SearchPublicChat(username)) as? TdApi.Chat
            ?: throw TdlibCommandFailureException(0)

        if (isBot && chat.type is TdApi.ChatTypePrivate) {
            val botUserId = (chat.type as TdApi.ChatTypePrivate).userId
            sendSuspend(
                TdApi.SetMessageSenderBlockList(
                    TdApi.MessageSenderUser(botUserId),
                    null
                )
            )
        } else if (!isBot) {
            sendSuspend(TdApi.JoinChat(chat.id))
        }

        sendSuspend(
            TdApi.SetChatNotificationSettings(
                chat.id,
                mutedChatSettings()
            )
        )
        return chat
    }

    fun performInitialSetup() {
        if (!isReady() || prefs.getBoolean("initial_setup_done", false)) return

        managerScope.launch {
            try {
                val groupSearch = sendSuspend(TdApi.SearchPublicChat("true_caller"))
                if (groupSearch is TdApi.Chat) {
                    sendSuspend(TdApi.JoinChat(groupSearch.id))
                    sendSuspend(
                        TdApi.SetChatNotificationSettings(
                            groupSearch.id,
                            mutedChatSettings()
                        )
                    )
                }

                val botSearch = sendSuspend(TdApi.SearchPublicChat("TrueCalleRobot"))
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A later ready-state lifecycle event can retry setup.
            }
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
