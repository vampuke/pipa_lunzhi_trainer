package com.vampuck.pipa_trainer

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vampuck.pipa_trainer.databinding.ActivityAccountBinding
import com.vampuck.pipa_trainer.databinding.DialogAccountCredentialsBinding
import com.vampuck.pipa_trainer.sync.AccountText
import com.vampuck.pipa_trainer.sync.ApiException
import com.vampuck.pipa_trainer.sync.PracticeSyncer
import com.vampuck.pipa_trainer.sync.SyncState
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class AccountActivity : AppCompatActivity() {
    private lateinit var b: ActivityAccountBinding
    private val syncer by lazy { PracticeSyncer.get(applicationContext) }
    private enum class Mode { LOGIN, REGISTER, RECOVER }
    private var mode = Mode.LOGIN
    private var operationBusy = false
    private var message: String? = null
    private var displayedRecovery: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Passwords and the once-only recovery code must not appear in task previews/screenshots.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        b = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(b.root)
        mode = Mode.values().firstOrNull { it.name == savedInstanceState?.getString("auth_mode") } ?: Mode.LOGIN
        b.deviceNameInput.setText(Build.MODEL.take(80).ifBlank { getString(R.string.account_default_device) })
        b.recoverySaved.isSaveEnabled = false
        b.authMode.check(if (mode == Mode.REGISTER) R.id.registerMode else R.id.loginMode)
        b.authMode.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                mode = if (checkedId == R.id.registerMode) Mode.REGISTER else Mode.LOGIN
                message = null
                b.passwordInput.text?.clear()
                lifecycleScope.launch { render() }
            }
        }
        b.recoverAccount.setOnClickListener {
            mode = if (mode == Mode.RECOVER) Mode.LOGIN else Mode.RECOVER
            if (mode == Mode.LOGIN) b.authMode.check(R.id.loginMode)
            message = null
            b.passwordInput.text?.clear()
            lifecycleScope.launch { render() }
        }
        b.submitAuth.setOnClickListener { submitAuth() }
        b.syncNow.setOnClickListener { perform { syncer.syncNow() } }
        b.changePassword.setOnClickListener { passwordDialog(changePassword = true) }
        b.regenerateRecovery.setOnClickListener { passwordDialog(changePassword = false) }
        b.logout.setOnClickListener {
            val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.account_logout)
                .setMessage(R.string.account_logout_notice).setNegativeButton(R.string.account_cancel, null)
                .setPositiveButton(R.string.account_logout) { _, _ -> perform { syncer.logout() } }.show()
            tintDialogButtons(dialog)
        }
        b.recoverySaved.setOnCheckedChangeListener { _, checked ->
            b.continueAccount.isEnabled = checked && !syncer.accountBusy.value && !operationBusy
        }
        b.continueAccount.setOnClickListener {
            if (b.recoverySaved.isChecked) {
                syncer.acknowledgeRecovery()
                b.recoveryCode.text = ""
                b.recoverySaved.isChecked = false
            }
        }
        b.backButton.setOnClickListener { leaveAccount() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { leaveAccount() }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(syncer.status, syncer.accountBusy, syncer.pendingRecovery) { _, _, _ -> Unit }
                    .collect { render() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PracticeSyncer.requestSync(applicationContext)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("auth_mode", mode.name)
        super.onSaveInstanceState(outState)
    }

    private fun leaveAccount() {
        when {
            syncer.pendingRecovery.value != null -> Toast.makeText(this, R.string.account_save_recovery_first, Toast.LENGTH_LONG).show()
            syncer.accountBusy.value || operationBusy -> Toast.makeText(this, R.string.account_working, Toast.LENGTH_SHORT).show()
            else -> finish()
        }
    }

    private suspend fun render() {
        val account = syncer.accountView()
        val recovery = syncer.pendingRecovery.value
        val busy = operationBusy || syncer.accountBusy.value
        val syncing = account.status.state == SyncState.SYNCING
        b.accountProgress.isVisible = busy || syncing
        b.guestPanel.isVisible = recovery == null && !account.loggedIn
        b.profilePanel.isVisible = recovery == null && account.loggedIn
        b.recoveryPanel.isVisible = recovery != null
        if (displayedRecovery != recovery) {
            displayedRecovery = recovery
            b.recoverySaved.isChecked = false
        }
        b.recoveryCode.text = recovery.orEmpty()
        b.continueAccount.isEnabled = recovery != null && b.recoverySaved.isChecked && !busy
        b.authMode.isVisible = mode != Mode.RECOVER
        b.recoverAccount.setText(if (mode == Mode.RECOVER) R.string.account_return_login else R.string.account_recover)
        b.recoveryInputLayout.isVisible = mode == Mode.RECOVER
        b.passwordInputLayout.hint = getString(if (mode == Mode.RECOVER) R.string.account_new_password else R.string.account_password)
        b.formHint.setText(when (mode) {
            Mode.LOGIN -> R.string.account_login_hint
            Mode.REGISTER -> R.string.account_register_hint
            Mode.RECOVER -> R.string.account_recover_hint
        })
        b.submitAuth.setText(when (mode) {
            Mode.LOGIN -> R.string.account_login
            Mode.REGISTER -> R.string.account_register
            Mode.RECOVER -> R.string.account_recover_submit
        })
        setControlsEnabled(!busy && !syncing)
        b.profileUsername.text = getString(R.string.account_username_format, account.username.orEmpty())
        account.expiresAt?.let { b.sessionExpiry.text = getString(R.string.account_expiry, AccountText.date(this, it * 1_000L)) }
        b.lastSync.text = getString(R.string.account_last_sync, AccountText.relativeTime(this, account.lastSyncAt))
        b.syncStatus.setText(when (account.status.state) {
            SyncState.IDLE -> R.string.account_sync_idle
            SyncState.SYNCING -> R.string.account_syncing
            SyncState.OK -> R.string.account_sync_ok
            SyncState.OFFLINE -> R.string.account_sync_offline
            SyncState.AUTH_REQUIRED -> R.string.account_auth_required
        })
        b.localOnlyNote.isVisible = account.localOnlySegments > 0
        val statusError = account.status.errorCode?.let {
            AccountText.error(this, ApiException(it, 0, account.status.retryAfter))
        }
        b.accountMessage.text = message ?: statusError.orEmpty()
        b.accountMessage.isVisible = message != null || statusError != null
    }

    private fun setControlsEnabled(enabled: Boolean) {
        listOf(b.loginMode, b.registerMode, b.usernameInput, b.passwordInput, b.recoveryInput,
            b.deviceNameInput, b.submitAuth, b.recoverAccount, b.syncNow, b.changePassword,
            b.regenerateRecovery, b.logout).forEach { it.isEnabled = enabled }
    }

    private fun submitAuth() {
        val username = Normalizer.normalize(b.usernameInput.text.toString(), Normalizer.Form.NFKC)
            .trim().lowercase(Locale.ROOT)
        val password = b.passwordInput.text.toString()
        val deviceName = b.deviceNameInput.text.toString().trim()
        val recovery = b.recoveryInput.text.toString().trim()
        val error = when {
            username.isEmpty() || password.isEmpty() || deviceName.isEmpty() -> R.string.account_error_required
            !username.matches(Regex("[a-z][a-z0-9_]{2,31}")) -> R.string.account_error_username
            mode != Mode.LOGIN && password.length !in 8..128 -> R.string.account_error_password
            mode == Mode.RECOVER && recovery.isEmpty() -> R.string.account_error_code_required
            else -> null
        }
        if (error != null) {
            message = getString(error)
            lifecycleScope.launch { render() }
            return
        }
        val selected = mode
        b.passwordInput.text?.clear()
        b.recoveryInput.text?.clear()
        perform {
            when (selected) {
                Mode.LOGIN -> syncer.login(username, password, deviceName)
                Mode.REGISTER -> syncer.register(username, password, deviceName)
                Mode.RECOVER -> syncer.recover(username, recovery, password, deviceName)
            }
        }
    }

    private fun passwordDialog(changePassword: Boolean) {
        val fields = DialogAccountCredentialsBinding.inflate(layoutInflater)
        fields.newPasswordLayout.isVisible = changePassword
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (changePassword) R.string.account_change_password else R.string.account_regenerate_recovery)
            .setMessage(if (changePassword) R.string.account_password_notice else R.string.account_regenerate_notice)
            .setView(fields.root).setNegativeButton(R.string.account_cancel, null)
            .setPositiveButton(R.string.account_confirm, null).create()
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            tintDialogButtons(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val current = fields.currentPassword.text.toString()
                val newPassword = fields.newPassword.text.toString()
                if (current.isEmpty()) {
                    fields.currentPassword.error = getString(R.string.account_error_current_password)
                } else if (changePassword && newPassword.length !in 8..128) {
                    fields.newPassword.error = getString(R.string.account_error_password)
                } else {
                    fields.currentPassword.text?.clear()
                    fields.newPassword.text?.clear()
                    dialog.dismiss()
                    perform {
                        if (changePassword) {
                            syncer.changePassword(current, newPassword)
                            Toast.makeText(this@AccountActivity, R.string.account_password_changed, Toast.LENGTH_SHORT).show()
                        } else syncer.regenerateRecoveryCode(current)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun tintDialogButtons(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.brand_text))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.brand_text))
    }

    private fun perform(action: suspend () -> Unit) {
        if (operationBusy || syncer.accountBusy.value) return
        operationBusy = true
        message = null
        setControlsEnabled(false)
        b.accountProgress.isVisible = true
        lifecycleScope.launch {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = AccountText.error(this@AccountActivity, error) }
            finally {
                operationBusy = false
                if (!isFinishing && !isDestroyed) render()
            }
        }
    }
}
