package com.videoclub.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.videoclub.app.R

/** What nginx asked for, waiting for somebody to type it. */
private class AuthRequest(val handler: HttpAuthHandler, val host: String, val realm: String)

/**
 * The VPS's panel, inside the app — held `Inicio`, three seconds, in a household the panel allows.
 *
 * Nothing that opens it is compiled in. Whether the gesture does anything at all arrives in the
 * household's document (`panelAccess`), and what the page itself stands behind is nginx's own
 * password, which is asked for here the first time and kept by the WebView on this device only — so
 * the APK every household installs carries neither the address's permission nor the password.
 *
 * The page stays on the panel's host: a link anywhere else is not followed. Back walks back through
 * the panel's own pages first, then leaves.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PanelScreen(url: String, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val host = remember(url) { Uri.parse(url).host.orEmpty() }
    var pending by remember { mutableStateOf<AuthRequest?>(null) }

    val webView = remember(url) {
        WebView(context).apply {
            // The panel is dark: without this, a white page flashes while it loads.
            setBackgroundColor(VideoclubColors.Surface.toArgb())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedHttpAuthRequest(
                    view: WebView,
                    handler: HttpAuthHandler,
                    authHost: String,
                    realm: String
                ) {
                    // A saved password is tried once; if nginx answers with the question again, it
                    // was wrong, and a person is asked instead of looping.
                    val saved = savedPassword(view, authHost, realm)
                    if (saved != null && handler.useHttpAuthUsernamePassword()) {
                        handler.proceed(saved.first, saved.second)
                    } else {
                        pending = AuthRequest(handler, authHost, realm)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != host
            }
            loadUrl(url)
        }
    }

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onLeave()
    }

    Box(modifier = modifier.fillMaxSize().background(VideoclubColors.Surface)) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        pending?.let { request ->
            PanelPasswordDialog(
                onSubmit = { user, password ->
                    savePassword(context, webView, request.host, request.realm, user, password)
                    request.handler.proceed(user, password)
                    pending = null
                },
                onCancel = {
                    request.handler.cancel()
                    pending = null
                    onLeave()
                }
            )
        }
    }
}

@Composable
private fun PanelPasswordDialog(onSubmit: (String, String) -> Unit, onCancel: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.panel_auth_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.login_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(user.trim(), password) }, enabled = user.isNotBlank() && password.isNotEmpty()) {
                Text(stringResource(R.string.login_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** The WebView's own store: private to this app, and kept across launches. */
private fun savedPassword(view: WebView, host: String, realm: String): Pair<String, String>? {
    val stored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WebViewDatabase.getInstance(view.context).getHttpAuthUsernamePassword(host, realm)
    } else {
        @Suppress("DEPRECATION")
        view.getHttpAuthUsernamePassword(host, realm)
    }
    val user = stored?.getOrNull(0) ?: return null
    val password = stored.getOrNull(1) ?: return null
    return user to password
}

private fun savePassword(context: Context, view: WebView, host: String, realm: String, user: String, password: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WebViewDatabase.getInstance(context).setHttpAuthUsernamePassword(host, realm, user, password)
    } else {
        @Suppress("DEPRECATION")
        view.setHttpAuthUsernamePassword(host, realm, user, password)
    }
}
