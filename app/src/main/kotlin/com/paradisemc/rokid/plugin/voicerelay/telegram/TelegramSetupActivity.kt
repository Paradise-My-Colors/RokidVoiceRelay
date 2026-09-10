package com.paradisemc.rokid.plugin.voicerelay.telegram

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class TelegramSetupActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val manager by lazy { TelegramClientManager.get(applicationContext) }

    private lateinit var statusView: TextView
    private lateinit var apiIdField: EditText
    private lateinit var apiHashField: EditText
    private lateinit var phoneField: EditText
    private lateinit var codeField: EditText
    private lateinit var passwordField: EditText
    private lateinit var emailField: EditText
    private lateinit var emailCodeField: EditText

    private lateinit var credentialButton: Button
    private lateinit var phoneButton: Button
    private lateinit var codeButton: Button
    private lateinit var passwordButton: Button
    private lateinit var emailButton: Button
    private lateinit var emailCodeButton: Button

    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            main.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Telegram setup"

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(36))
        }
        setContentView(ScrollView(this).apply { addView(content) })

        content.addView(text("Telegram setup", 26f, true))
        content.addView(
            text(
                "Voice Relay uses a private TDLib session on this phone. Your api_hash, phone login state and TDLib database key stay on the device; do not send them to anyone.",
                14f,
                false,
            ),
        )
        spacer(content, 18)

        statusView = text("Starting Telegram…", 16f, true)
        content.addView(statusView)
        spacer(content, 16)

        content.addView(text("Telegram API credentials", 19f, true))
        apiIdField = field("api_id", InputType.TYPE_CLASS_NUMBER)
        apiHashField = field(
            "api_hash",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        content.addView(apiIdField)
        content.addView(apiHashField)
        credentialButton = button("Save credentials & start") { saveCredentials() }
        content.addView(credentialButton)

        val savedCredentials = TelegramSecureStore.credentials(this)
        if (savedCredentials != null) {
            apiIdField.setText(savedCredentials.apiId.toString())
            apiHashField.hint = "api_hash saved securely — leave blank to keep it"
        }

        spacer(content, 22)
        content.addView(text("Telegram account login", 19f, true))

        phoneField = field(
            "Phone number, e.g. +491234567890",
            InputType.TYPE_CLASS_PHONE,
        )
        TelegramSecureStore.phone(this)?.let(phoneField::setText)
        phoneButton = button("Send login code") {
            manager.submitPhone(phoneField.text.toString())
        }
        content.addView(phoneField)
        content.addView(phoneButton)

        emailField = field(
            "Email address",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )
        emailButton = button("Send email code") {
            manager.submitEmail(emailField.text.toString())
        }
        content.addView(emailField)
        content.addView(emailButton)

        emailCodeField = field("Email code", InputType.TYPE_CLASS_TEXT)
        emailCodeButton = button("Verify email code") {
            manager.submitEmailCode(emailCodeField.text.toString())
        }
        content.addView(emailCodeField)
        content.addView(emailCodeButton)

        codeField = field("Telegram login code", InputType.TYPE_CLASS_TEXT)
        codeButton = button("Verify login code") {
            manager.submitCode(codeField.text.toString())
        }
        content.addView(codeField)
        content.addView(codeButton)

        passwordField = field(
            "2-step verification password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        passwordButton = button("Verify password") {
            manager.submitPassword(passwordField.text.toString())
        }
        content.addView(passwordField)
        content.addView(passwordButton)

        spacer(content, 22)
        content.addView(
            text(
                "After the status becomes CONNECTED, return to Voice Relay. Glasses replies can then be encoded as OGG/Opus and sent through this Telegram session.",
                14f,
                false,
            ),
        )

        manager.start()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        manager.start()
        main.removeCallbacks(refresh)
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private fun saveCredentials() {
        val id = apiIdField.text.toString().trim().toIntOrNull()
        if (id == null || id <= 0) {
            statusView.text = "Enter a valid numeric api_id."
            return
        }

        val existing = TelegramSecureStore.credentials(this)
        val typedHash = apiHashField.text.toString().trim()
        val hash = typedHash.ifBlank { existing?.apiHash.orEmpty() }
        if (hash.isBlank()) {
            statusView.text = "Enter your api_hash."
            return
        }

        manager.saveCredentials(id, hash).fold(
            onSuccess = {
                apiHashField.text.clear()
                apiHashField.hint = "api_hash saved securely — leave blank to keep it"
                statusView.text = "Credentials saved. Starting Telegram…"
            },
            onFailure = {
                statusView.text = it.message ?: "Could not save Telegram credentials."
            },
        )
    }

    private fun renderStatus() {
        val status = manager.status()
        statusView.text = buildString {
            append(
                when (status.stage) {
                    TelegramAuthStage.READY -> "Telegram: CONNECTED"
                    TelegramAuthStage.NEED_CREDENTIALS -> "Telegram: NEEDS API CREDENTIALS"
                    TelegramAuthStage.NEED_PHONE -> "Telegram: NEEDS PHONE NUMBER"
                    TelegramAuthStage.NEED_CODE -> "Telegram: NEEDS LOGIN CODE"
                    TelegramAuthStage.NEED_PASSWORD -> "Telegram: NEEDS 2FA PASSWORD"
                    TelegramAuthStage.NEED_EMAIL -> "Telegram: NEEDS EMAIL"
                    TelegramAuthStage.NEED_EMAIL_CODE -> "Telegram: NEEDS EMAIL CODE"
                    TelegramAuthStage.ERROR -> "Telegram: ERROR"
                    TelegramAuthStage.CLOSED -> "Telegram: CLOSED"
                    else -> "Telegram: STARTING"
                },
            )
            status.accountLabel?.let { append("\nAccount: $it") }
            append("\n${status.detail}")
        }

        val credentialVisibility =
            if (status.stage == TelegramAuthStage.READY) View.GONE else View.VISIBLE
        apiIdField.visibility = credentialVisibility
        apiHashField.visibility = credentialVisibility
        credentialButton.visibility = credentialVisibility

        show(phoneField, phoneButton, status.stage == TelegramAuthStage.NEED_PHONE)
        show(emailField, emailButton, status.stage == TelegramAuthStage.NEED_EMAIL)
        show(emailCodeField, emailCodeButton, status.stage == TelegramAuthStage.NEED_EMAIL_CODE)
        show(codeField, codeButton, status.stage == TelegramAuthStage.NEED_CODE)
        show(passwordField, passwordButton, status.stage == TelegramAuthStage.NEED_PASSWORD)
    }

    private fun show(field: View, button: View, visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        field.visibility = visibility
        button.visibility = visibility
    }

    private fun field(hintText: String, type: Int): EditText =
        EditText(this).apply {
            hint = hintText
            inputType = type
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }

    private fun text(value: String, size: Float, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
        }

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }

    private fun spacer(parent: LinearLayout, heightDp: Int) {
        parent.addView(TextView(this), LinearLayout.LayoutParams(1, dp(heightDp)))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
