package com.videoclub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.videoclub.app.R
import com.videoclub.app.data.LoginOutcome
import kotlinx.coroutines.launch

/**
 * The general APK's first screen: which household this device belongs to.
 *
 * Shown the first time, or after the app's data is cleared, and never again — there is no way back
 * to it from inside the app, on purpose: a device does not change households.
 *
 * Built for a remote as much as for a finger. The cursor starts in the username, the keyboard's own
 * "next" goes to the password, and its "done" is the same as pressing Entrar.
 */
@Composable
fun LoginScreen(
    onLogIn: suspend (username: String, password: String) -> LoginOutcome,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false
) {
    val skin = LocalSkin.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || username.isBlank() || password.isEmpty()) return
        busy = true
        message = null
        scope.launch {
            message = when (val outcome = onLogIn(username, password)) {
                // The container moves on by itself, and this screen with it.
                is LoginOutcome.Success -> null
                LoginOutcome.Refused -> context.getString(R.string.login_refused)
                is LoginOutcome.TooManyAttempts -> context.getString(
                    R.string.login_too_many,
                    ((outcome.retryAfterSeconds + 59) / 60).coerceAtLeast(1)
                )
                LoginOutcome.Unreachable -> context.getString(R.string.login_unreachable)
            }
            busy = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VideoclubColors.Surface)
            .padding(horizontal = skin.screenPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Label(text = stringResource(R.string.login_title), style = skin.heroTitle)
        Spacer(Modifier.height(skin.rowGap / 2))
        Label(
            text = stringResource(R.string.login_note),
            style = skin.body,
            color = VideoclubColors.TextSecondary
        )
        Spacer(Modifier.height(skin.rowGap * 2))

        Column(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(skin.rowGap / 2)
        ) {
            LoginField(
                value = username,
                onValueChange = { username = it },
                hint = stringResource(R.string.login_username),
                imeAction = ImeAction.Next,
                onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                autoFocus = autoFocus
            )
            LoginField(
                value = password,
                onValueChange = { password = it },
                hint = stringResource(R.string.login_password),
                imeAction = ImeAction.Done,
                onImeAction = ::submit,
                secret = true
            )
            ActionButton(
                text = stringResource(if (busy) R.string.login_busy else R.string.login_submit),
                onClick = ::submit,
                filled = true,
                modifier = Modifier.align(Alignment.End)
            )
            message?.let { Label(text = it, style = skin.body, color = VideoclubColors.Accent) }
        }
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    secret: Boolean = false,
    autoFocus: Boolean = false
) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = skin.body.copy(color = VideoclubColors.TextPrimary),
        cursorBrush = SolidColor(VideoclubColors.Accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(VideoclubColors.SurfaceElevated)
            .border(
                width = 2.dp,
                color = if (focused) VideoclubColors.TextPrimary else VideoclubColors.SurfaceElevated,
                shape = shape
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { field ->
            if (value.isEmpty()) {
                Label(text = hint, style = skin.body, color = VideoclubColors.TextSecondary, maxLines = 1)
            }
            field()
        }
    )
}
