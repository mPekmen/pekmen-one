package net.pekmen.one.account

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.pekmen.one.R
import net.pekmen.one.core.cloud.auth.IdentityError
import net.pekmen.one.core.cloud.auth.IdentityFailure

private enum class AuthPage { WELCOME, SIGN_IN, REGISTER, VERIFY, RESET, RESET_SENT }

@Composable
@Suppress("CyclomaticComplexMethod") // Mutually exclusive pages share the same transient form state.
internal fun AuthScreen(accounts: PekmenAccounts, linkError: IdentityError?) {
    var page by rememberSaveable { mutableStateOf(AuthPage.WELCOME) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val action = rememberAccountAction()
    val context = LocalContext.current
    val goTo: (AuthPage) -> Unit = { target ->
        page = target
        password = ""
        action.clear()
    }
    val goBack = {
        val previous = when (page) {
            AuthPage.RESET, AuthPage.RESET_SENT, AuthPage.VERIFY -> AuthPage.SIGN_IN
            else -> AuthPage.WELCOME
        }
        goTo(previous)
    }
    BackHandler(page != AuthPage.WELCOME && !action.busy, goBack)

    AccountPage {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        if (!accounts.isConfigured) {
            Message(R.string.auth_configuration)
            return@AccountPage
        }
        if (page == AuthPage.WELCOME) {
            Button(onClick = { goTo(AuthPage.SIGN_IN) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.auth_sign_in))
            }
            OutlinedButton(
                onClick = { goTo(AuthPage.REGISTER) },
                enabled = accounts.hasVerificationLinks,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.auth_register)) }
        } else {
            when (page) {
                AuthPage.VERIFY, AuthPage.RESET_SENT -> {
                    Message(if (page == AuthPage.VERIFY) R.string.auth_verify_mail else R.string.auth_reset_sent)
                    TextButton(onClick = { goTo(AuthPage.SIGN_IN) }, enabled = !action.busy) {
                        Text(stringResource(R.string.auth_sign_in))
                    }
                }
                AuthPage.SIGN_IN, AuthPage.REGISTER, AuthPage.RESET -> {
                    val title = when (page) {
                        AuthPage.REGISTER -> R.string.auth_register
                        AuthPage.RESET -> R.string.auth_reset
                        else -> R.string.auth_sign_in
                    }
                    Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.auth_email)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        enabled = !action.busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (page != AuthPage.RESET) {
                        PasswordField(password, { password = it }, !action.busy)
                    }
                    val canSubmit = email.trim().contains('@') && when (page) {
                        AuthPage.RESET -> true
                        AuthPage.REGISTER -> password.length >= MIN_PASSWORD_LENGTH
                        else -> password.isNotBlank()
                    }
                    Button(
                        onClick = {
                            action.run {
                                when (page) {
                                    AuthPage.REGISTER -> {
                                        accounts.signUp(email, password)
                                        goTo(AuthPage.VERIFY)
                                    }
                                    AuthPage.RESET -> {
                                        accounts.requestPasswordReset(email)
                                        goTo(AuthPage.RESET_SENT)
                                    }
                                    else -> accounts.signIn(email, password)
                                }
                            }
                        },
                        enabled = canSubmit && !action.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(if (page == AuthPage.RESET) R.string.auth_send_link else title)) }
                    if (page == AuthPage.SIGN_IN && accounts.hasVerificationLinks) {
                        TextButton(onClick = { goTo(AuthPage.RESET) }, enabled = !action.busy) {
                            Text(stringResource(R.string.auth_forgot))
                        }
                    }
                }
                AuthPage.WELCOME -> Unit
            }
        }

        if (page in listOf(AuthPage.WELCOME, AuthPage.SIGN_IN, AuthPage.REGISTER)) {
            OutlinedButton(
                enabled = accounts.hasVerificationLinks && !action.busy,
                onClick = {
                    action.run {
                        val authorization = accounts.beginGoogle()
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorization.toASCIIString())))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.auth_google)) }
            if (!accounts.hasVerificationLinks) Message(R.string.auth_redirect_missing)
        }
        if (page != AuthPage.WELCOME) {
            TextButton(onClick = goBack, enabled = !action.busy) {
                Text(stringResource(R.string.account_back))
            }
        }
        AccountActionFeedback(action, linkError)
    }
}

@Composable
internal fun PasswordRecoveryScreen(accounts: PekmenAccounts) {
    var password by remember { mutableStateOf("") }
    val action = rememberAccountAction()
    BackHandler(!action.busy) { action.run { accounts.cancelRecovery() } }
    AccountPage {
        Text(stringResource(R.string.auth_reset), style = MaterialTheme.typography.headlineSmall)
        PasswordField(password, { password = it }, !action.busy)
        Button(enabled = password.length >= MIN_PASSWORD_LENGTH && !action.busy, onClick = {
            action.run { accounts.changePassword(password) }
        }) { Text(stringResource(R.string.account_save)) }
        TextButton(enabled = !action.busy, onClick = { action.run { accounts.cancelRecovery() } }) {
            Text(stringResource(R.string.account_back))
        }
        AccountActionFeedback(action)
    }
}

@Composable
private fun PasswordField(password: String, change: (String) -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = password,
        onValueChange = change,
        label = { Text(stringResource(R.string.auth_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun AccountPage(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
    }
}

@Stable
internal class AccountAction(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<IdentityError?>(null)
        private set

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action()
            } catch (failure: IdentityFailure) {
                error = failure.reason
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = IdentityError.SERVICE
            } finally {
                busy = false
            }
        }
    }

    fun clear() {
        error = null
    }
}

@Composable
internal fun rememberAccountAction(): AccountAction {
    val scope = rememberCoroutineScope()
    return remember(scope) { AccountAction(scope) }
}

@Composable
internal fun AccountActionFeedback(action: AccountAction, linkError: IdentityError? = null) {
    if (action.busy) CircularProgressIndicator()
    val error = action.error ?: linkError
    if (error != null) {
        Text(
            stringResource(identityErrorResource(error)),
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
internal fun Message(@StringRes resource: Int) {
    Text(stringResource(resource), style = MaterialTheme.typography.bodyMedium)
}

@StringRes
private fun identityErrorResource(error: IdentityError): Int = when (error) {
    IdentityError.CONFIGURATION -> R.string.auth_configuration
    IdentityError.CONNECTION -> R.string.auth_connection
    IdentityError.CREDENTIALS -> R.string.auth_credentials
    IdentityError.EMAIL_UNVERIFIED -> R.string.auth_email_unverified
    IdentityError.RATE_LIMIT -> R.string.auth_rate_limit
    IdentityError.LINK -> R.string.auth_invalid_link
    IdentityError.SERVICE -> R.string.auth_service
}

private const val MIN_PASSWORD_LENGTH = 8
