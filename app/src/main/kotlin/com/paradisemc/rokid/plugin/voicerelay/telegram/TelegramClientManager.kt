package com.paradisemc.rokid.plugin.voicerelay.telegram

import android.content.Context
import android.os.Build
import com.paradisemc.rokid.plugin.voicerelay.IncomingMessage
import io.github.up9cloud.td.JsonClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

enum class TelegramAuthStage {
    STOPPED,
    STARTING,
    NEED_CREDENTIALS,
    NEED_PHONE,
    NEED_EMAIL,
    NEED_EMAIL_CODE,
    NEED_CODE,
    NEED_PASSWORD,
    READY,
    ERROR,
    CLOSED,
}

data class TelegramStatus(
    val stage: TelegramAuthStage,
    val detail: String,
    val accountLabel: String? = null,
)

class TelegramClientManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val requestCounter = AtomicLong(1)
    private val callbacks = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private val pendingSends = ConcurrentHashMap<String, (Result<Long>) -> Unit>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var started = false
    @Volatile private var clientId = 0
    @Volatile private var running = false
    @Volatile private var currentStatus = TelegramStatus(
        TelegramAuthStage.STOPPED,
        "Telegram client is stopped.",
    )

    fun status(): TelegramStatus = currentStatus
    fun isReady(): Boolean = currentStatus.stage == TelegramAuthStage.READY

    fun start() {
        synchronized(lock) {
            if (started) return
            val credentials = TelegramSecureStore.credentials(appContext)
            if (credentials == null) {
                currentStatus = TelegramStatus(
                    TelegramAuthStage.NEED_CREDENTIALS,
                    "Enter your Telegram api_id and api_hash.",
                )
                return
            }

            currentStatus = TelegramStatus(TelegramAuthStage.STARTING, "Starting Telegram…")
            try {
                JsonClient.td_execute(
                    JSONObject()
                        .put("@type", "setLogVerbosityLevel")
                        .put("new_verbosity_level", 1)
                        .toString(),
                )
                clientId = JsonClient.td_create_client_id()
            } catch (error: Throwable) {
                currentStatus = TelegramStatus(
                    TelegramAuthStage.ERROR,
                    "TDLib could not start: ${error.message ?: error.javaClass.simpleName}",
                )
                return
            }

            started = true
            running = true
            thread(name = "VoiceRelay-TDLib", isDaemon = true) { receiveLoop() }
            request(JSONObject().put("@type", "getAuthorizationState")) { state ->
                if (state.optString("@type") == "error") setError(state)
                else handleAuthorizationState(state)
            }
        }
    }

    fun saveCredentials(apiId: Int, apiHash: String): Result<Unit> {
        if (apiId <= 0) return Result.failure(IllegalArgumentException("api_id must be a positive number."))
        if (apiHash.isBlank()) return Result.failure(IllegalArgumentException("api_hash is required."))

        return runCatching {
            TelegramSecureStore.saveCredentials(appContext, apiId, apiHash)
            if (!started) start()
            else request(JSONObject().put("@type", "getAuthorizationState")) { state ->
                handleAuthorizationState(state)
            }
        }
    }

    fun submitPhone(phone: String) {
        val normalized = phone.trim()
        if (normalized.isBlank()) return
        TelegramSecureStore.savePhone(appContext, normalized)
        requestAuth(
            JSONObject()
                .put("@type", "setAuthenticationPhoneNumber")
                .put("phone_number", normalized)
                .put("settings", JSONObject.NULL),
            TelegramAuthStage.NEED_PHONE,
        )
    }

    fun submitCode(code: String) {
        requestAuth(
            JSONObject().put("@type", "checkAuthenticationCode").put("code", code.trim()),
            TelegramAuthStage.NEED_CODE,
        )
    }

    fun submitPassword(password: String) {
        requestAuth(
            JSONObject().put("@type", "checkAuthenticationPassword").put("password", password),
            TelegramAuthStage.NEED_PASSWORD,
        )
    }

    fun submitEmail(email: String) {
        requestAuth(
            JSONObject().put("@type", "setAuthenticationEmailAddress").put("email_address", email.trim()),
            TelegramAuthStage.NEED_EMAIL,
        )
    }

    fun submitEmailCode(code: String) {
        val value = JSONObject()
            .put("@type", "emailAddressAuthenticationCode")
            .put("code", code.trim())
        requestAuth(
            JSONObject().put("@type", "checkAuthenticationEmailCode").put("code", value),
            TelegramAuthStage.NEED_EMAIL_CODE,
        )
    }

    fun sendVoiceNote(
        target: IncomingMessage,
        oggPath: String,
        durationSeconds: Int,
        callback: (Result<Long>) -> Unit,
    ) {
        if (!isReady()) {
            callback(Result.failure(IllegalStateException("Telegram is not logged in. Open Telegram setup on the phone.")))
            return
        }

        resolveChat(target) { chatResult ->
            chatResult.fold(
                onSuccess = { chatId ->
                    val content = JSONObject()
                        .put("@type", "inputMessageVoiceNote")
                        .put(
                            "voice_note",
                            JSONObject().put("@type", "inputFileLocal").put("path", oggPath),
                        )
                        .put("duration", durationSeconds.coerceAtLeast(1))
                        .put("waveform", "")
                        .put("caption", JSONObject.NULL)
                        .put("self_destruct_type", JSONObject.NULL)

                    val send = JSONObject()
                        .put("@type", "sendMessage")
                        .put("chat_id", chatId)
                        .put("topic_id", JSONObject.NULL)
                        .put("reply_to", JSONObject.NULL)
                        .put("options", JSONObject.NULL)
                        .put("reply_markup", JSONObject.NULL)
                        .put("input_message_content", content)

                    request(send) { response ->
                        if (response.optString("@type") == "error") {
                            callback(Result.failure(tdError(response)))
                            return@request
                        }

                        val returnedChatId = response.optLong("chat_id", chatId)
                        val messageId = response.optLong("id", 0L)
                        if (messageId == 0L) {
                            callback(Result.failure(IllegalStateException("Telegram returned no message id.")))
                            return@request
                        }

                        val sendingState = response.optJSONObject("sending_state")
                        if (sendingState == null) {
                            callback(Result.success(returnedChatId))
                            return@request
                        }

                        val key = sendKey(returnedChatId, messageId)
                        pendingSends[key] = callback
                        scheduler.schedule({
                            pendingSends.remove(key)?.invoke(
                                Result.failure(IllegalStateException("Telegram send confirmation timed out.")),
                            )
                        }, 60, TimeUnit.SECONDS)
                    }
                },
                onFailure = { error -> callback(Result.failure(error)) },
            )
        }
    }

    private fun resolveChat(target: IncomingMessage, callback: (Result<Long>) -> Unit) {
        val shortcut = target.shortcutId.orEmpty()
        val candidate = Regex("^ndid_(-?\\d+)$").matchEntire(shortcut)
            ?.groupValues?.getOrNull(1)?.toLongOrNull()

        if (candidate != null && candidate > 0L) {
            request(
                JSONObject()
                    .put("@type", "createPrivateChat")
                    .put("user_id", candidate)
                    .put("force", false),
            ) { response ->
                if (response.optString("@type") != "error") {
                    val type = response.optJSONObject("type")
                    val userId = type?.optLong("user_id", Long.MIN_VALUE)
                    if (type?.optString("@type") == "chatTypePrivate" && userId == candidate) {
                        callback(Result.success(response.optLong("id")))
                        return@request
                    }
                }
                resolveByTitle(target.sender, callback)
            }
            return
        }

        if (candidate != null && candidate < 0L) {
            request(JSONObject().put("@type", "getChat").put("chat_id", candidate)) { response ->
                if (response.optString("@type") != "error" && sameTitle(response.optString("title"), target.sender)) {
                    callback(Result.success(response.optLong("id")))
                } else {
                    resolveByTitle(target.sender, callback)
                }
            }
            return
        }

        resolveByTitle(target.sender, callback)
    }

    private fun resolveByTitle(sender: String, callback: (Result<Long>) -> Unit) {
        searchExact(sender, false) { local ->
            if (local.isSuccess && local.getOrNull() != null) {
                callback(Result.success(local.getOrThrow()!!))
                return@searchExact
            }
            searchExact(sender, true) { remote ->
                remote.fold(
                    onSuccess = { chatId ->
                        if (chatId == null) {
                            callback(Result.failure(IllegalStateException("Could not uniquely match Telegram conversation “$sender”.")))
                        } else callback(Result.success(chatId))
                    },
                    onFailure = { callback(Result.failure(it)) },
                )
            }
        }
    }

    private fun searchExact(query: String, server: Boolean, callback: (Result<Long?>) -> Unit) {
        val function = if (server) "searchChatsOnServer" else "searchChats"
        request(
            JSONObject().put("@type", function).put("query", query).put("limit", 20),
        ) { response ->
            if (response.optString("@type") == "error") {
                callback(Result.failure(tdError(response)))
                return@request
            }
            val ids = response.optJSONArray("chat_ids") ?: JSONArray()
            fetchExactTitles(ids, query, 0, mutableListOf()) { result ->
                result.fold(
                    onSuccess = { matches -> callback(Result.success(matches.singleOrNull())) },
                    onFailure = { callback(Result.failure(it)) },
                )
            }
        }
    }

    private fun fetchExactTitles(
        ids: JSONArray,
        expectedTitle: String,
        index: Int,
        matches: MutableList<Long>,
        callback: (Result<List<Long>>) -> Unit,
    ) {
        if (index >= ids.length()) {
            callback(Result.success(matches))
            return
        }
        val id = ids.optLong(index)
        request(JSONObject().put("@type", "getChat").put("chat_id", id)) { response ->
            if (response.optString("@type") != "error" && sameTitle(response.optString("title"), expectedTitle)) {
                matches += response.optLong("id")
            }
            if (matches.size > 1) callback(Result.success(matches))
            else fetchExactTitles(ids, expectedTitle, index + 1, matches, callback)
        }
    }

    private fun requestAuth(request: JSONObject, stage: TelegramAuthStage) {
        if (!started) start()
        if (!started) return
        currentStatus = TelegramStatus(stage, "Submitting…", currentStatus.accountLabel)
        request(request) { response ->
            if (response.optString("@type") == "error") {
                currentStatus = TelegramStatus(
                    stage,
                    tdError(response).message ?: "Telegram rejected the request.",
                    currentStatus.accountLabel,
                )
            }
        }
    }

    private fun receiveLoop() {
        while (running) {
            val raw = try {
                JsonClient.td_receive(1.0)
            } catch (error: Throwable) {
                currentStatus = TelegramStatus(
                    TelegramAuthStage.ERROR,
                    "TDLib receive failed: ${error.message ?: error.javaClass.simpleName}",
                )
                break
            } ?: continue

            val response = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val responseClientId = response.optInt("@client_id", clientId)
            if (responseClientId != clientId) continue

            val extra = response.opt("@extra")?.toString()
            if (!extra.isNullOrBlank()) callbacks.remove(extra)?.invoke(response)

            when (response.optString("@type")) {
                "updateAuthorizationState" -> response.optJSONObject("authorization_state")?.let(::handleAuthorizationState)
                "updateMessageSendSucceeded" -> handleSendSucceeded(response)
                "updateMessageSendFailed" -> handleSendFailed(response)
            }
        }
    }

    private fun handleAuthorizationState(state: JSONObject) {
        when (state.optString("@type")) {
            "authorizationStateWaitTdlibParameters" -> {
                val credentials = TelegramSecureStore.credentials(appContext)
                if (credentials == null) {
                    currentStatus = TelegramStatus(TelegramAuthStage.NEED_CREDENTIALS, "Enter your Telegram api_id and api_hash.")
                    return
                }

                val database = File(appContext.filesDir, "telegram/tdlib-db").apply { mkdirs() }
                val files = File(appContext.filesDir, "telegram/files").apply { mkdirs() }
                currentStatus = TelegramStatus(TelegramAuthStage.STARTING, "Initializing Telegram…")

                val parameters = JSONObject()
                    .put("@type", "setTdlibParameters")
                    .put("use_test_dc", false)
                    .put("database_directory", database.absolutePath)
                    .put("files_directory", files.absolutePath)
                    .put("database_encryption_key", TelegramSecureStore.databaseKeyBase64(appContext))
                    .put("use_file_database", true)
                    .put("use_chat_info_database", true)
                    .put("use_message_database", true)
                    .put("use_secret_chats", false)
                    .put("api_id", credentials.apiId)
                    .put("api_hash", credentials.apiHash)
                    .put("system_language_code", Locale.getDefault().toLanguageTag().ifBlank { "en" })
                    .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" })
                    .put("system_version", "Android ${Build.VERSION.RELEASE}")
                    .put("application_version", "0.4.0")

                request(parameters) { response ->
                    if (response.optString("@type") == "error") setError(response)
                }
            }
            "authorizationStateWaitPhoneNumber" -> currentStatus = TelegramStatus(
                TelegramAuthStage.NEED_PHONE,
                "Enter the phone number for your Telegram account.",
            )
            "authorizationStateWaitEmailAddress" -> currentStatus = TelegramStatus(
                TelegramAuthStage.NEED_EMAIL,
                "Telegram requires an email address for this login.",
            )
            "authorizationStateWaitEmailCode" -> currentStatus = TelegramStatus(
                TelegramAuthStage.NEED_EMAIL_CODE,
                "Enter the code Telegram sent to your email.",
            )
            "authorizationStateWaitCode" -> {
                val info = state.optJSONObject("code_info")
                val phone = info?.optString("phone_number").orEmpty()
                currentStatus = TelegramStatus(
                    TelegramAuthStage.NEED_CODE,
                    if (phone.isBlank()) "Enter the Telegram login code." else "Enter the Telegram login code for $phone.",
                )
            }
            "authorizationStateWaitPassword" -> {
                val hint = state.optString("password_hint")
                currentStatus = TelegramStatus(
                    TelegramAuthStage.NEED_PASSWORD,
                    if (hint.isBlank()) "Enter your Telegram 2-step verification password."
                    else "Enter your Telegram 2-step verification password. Hint: $hint",
                )
            }
            "authorizationStateReady" -> {
                currentStatus = TelegramStatus(TelegramAuthStage.READY, "Telegram connected.")
                request(JSONObject().put("@type", "getMe")) { response ->
                    if (response.optString("@type") != "error") {
                        val first = response.optString("first_name")
                        val last = response.optString("last_name")
                        val label = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
                            .ifBlank { response.optString("phone_number") }
                        currentStatus = TelegramStatus(
                            TelegramAuthStage.READY,
                            "Telegram connected.",
                            label.takeIf { it.isNotBlank() },
                        )
                    }
                }
            }
            "authorizationStateClosing" -> currentStatus = TelegramStatus(TelegramAuthStage.STARTING, "Telegram is closing…")
            "authorizationStateLoggingOut" -> currentStatus = TelegramStatus(TelegramAuthStage.STARTING, "Telegram is logging out…")
            "authorizationStateClosed" -> {
                running = false
                started = false
                clientId = 0
                currentStatus = TelegramStatus(TelegramAuthStage.CLOSED, "Telegram client closed.")
            }
            "authorizationStateWaitOtherDeviceConfirmation" -> currentStatus = TelegramStatus(
                TelegramAuthStage.ERROR,
                "Telegram requested confirmation on another device. Complete it in Telegram, then return here.",
            )
            "authorizationStateWaitRegistration" -> currentStatus = TelegramStatus(
                TelegramAuthStage.ERROR,
                "This prototype does not create new Telegram accounts. Sign in with an existing account.",
            )
            "authorizationStateWaitPremiumPurchase" -> currentStatus = TelegramStatus(
                TelegramAuthStage.ERROR,
                "Telegram requires an additional account step before this client can sign in.",
            )
            else -> currentStatus = TelegramStatus(
                TelegramAuthStage.STARTING,
                "Telegram authorization: ${state.optString("@type", "unknown")}",
            )
        }
    }

    private fun request(request: JSONObject, callback: ((JSONObject) -> Unit)? = null) {
        if (!started || clientId == 0) {
            callback?.invoke(
                JSONObject().put("@type", "error").put("code", 500).put("message", "TDLib is not started."),
            )
            return
        }

        val extra = "vr-${requestCounter.getAndIncrement()}"
        if (callback != null) {
            callbacks[extra] = callback
            scheduler.schedule({
                callbacks.remove(extra)?.invoke(
                    JSONObject().put("@type", "error").put("code", 408).put("message", "Telegram request timed out."),
                )
            }, 30, TimeUnit.SECONDS)
        }
        request.put("@extra", extra)

        try {
            JsonClient.td_send(clientId, request.toString())
        } catch (error: Throwable) {
            callbacks.remove(extra)
            callback?.invoke(
                JSONObject().put("@type", "error").put("code", 500)
                    .put("message", error.message ?: error.javaClass.simpleName),
            )
        }
    }

    private fun handleSendSucceeded(update: JSONObject) {
        val message = update.optJSONObject("message") ?: return
        val chatId = message.optLong("chat_id")
        val oldMessageId = update.optLong("old_message_id")
        pendingSends.remove(sendKey(chatId, oldMessageId))?.invoke(Result.success(chatId))
    }

    private fun handleSendFailed(update: JSONObject) {
        val message = update.optJSONObject("message") ?: return
        val chatId = message.optLong("chat_id")
        val oldMessageId = update.optLong("old_message_id")
        val error = update.optJSONObject("error")
        pendingSends.remove(sendKey(chatId, oldMessageId))?.invoke(
            Result.failure(
                IllegalStateException(
                    error?.optString("message")?.takeIf { it.isNotBlank() }
                        ?: "Telegram failed to send the voice note.",
                ),
            ),
        )
    }

    private fun setError(error: JSONObject) {
        currentStatus = TelegramStatus(
            TelegramAuthStage.ERROR,
            tdError(error).message ?: "Telegram error.",
        )
    }

    private fun tdError(response: JSONObject): IllegalStateException {
        val code = response.optInt("code", 0)
        val message = response.optString("message", "Unknown Telegram error")
        return IllegalStateException(if (code == 0) message else "Telegram $code: $message")
    }

    private fun sameTitle(a: String, b: String): Boolean = normalizeTitle(a) == normalizeTitle(b)

    private fun normalizeTitle(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun sendKey(chatId: Long, messageId: Long): String = "$chatId:$messageId"

    companion object {
        @Volatile private var instance: TelegramClientManager? = null

        fun get(context: Context): TelegramClientManager =
            instance ?: synchronized(this) {
                instance ?: TelegramClientManager(context).also { instance = it }
            }
    }
}
