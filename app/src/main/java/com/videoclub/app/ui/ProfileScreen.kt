package com.videoclub.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.videoclub.app.R
import com.videoclub.app.data.Profile

/**
 * Who is watching, asked every time the app opens.
 *
 * Every time, and not only the first: the people who share this account share the television as
 * well, and a device that remembers the last answer is a device that quietly writes tonight into
 * somebody else's `Seguir viendo`. One press is a cheap price for that not happening. It matters
 * more now than it did with two: the third profile is a child's, and what it changes is not only
 * whose history is written but what the whole app is showing.
 *
 * [suggested] is whoever watched last here, and it only decides where the cursor starts — on a
 * television that is the difference between one press of the remote and three. On a phone nothing
 * is focused, so it makes no difference at all.
 */
@Composable
fun ProfileScreen(
    people: List<Profile>,
    suggested: Profile,
    onChoose: (Profile) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false
) {
    val skin = LocalSkin.current

    Column(
        modifier = modifier.fillMaxSize().background(VideoclubColors.Surface),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Label(text = stringResource(R.string.profile_question), style = skin.heroTitle)
        Spacer(Modifier.height(skin.rowGap * 2))
        Row(horizontalArrangement = Arrangement.spacedBy(skin.rowGap)) {
            people.forEach { profile ->
                ProfileAvatar(
                    profile = profile,
                    onClick = { onChoose(profile) },
                    autoFocus = autoFocus && profile == suggested
                )
            }
        }
    }
}

@Composable
private fun ProfileAvatar(
    profile: Profile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false
) {
    val skin = LocalSkin.current
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) skin.focusScale else 1f,
        label = "profileScale"
    )
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = modifier
            .scale(scale)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            // As wide as a poster, so the two of them read as cards on the same shelf as everything
            // else the app draws, at whatever size this device draws them.
            modifier = Modifier
                .size(skin.posterWidth)
                .clip(CircleShape)
                .background(
                    if (focused) VideoclubColors.TextPrimary
                    else VideoclubColors.avatarColor(profile.id)
                )
                .then(
                    if (focused) Modifier.border(3.dp, VideoclubColors.TextPrimary, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Label(
                text = profile.initial,
                style = skin.heroTitle,
                color = if (focused) VideoclubColors.Surface else VideoclubColors.TextPrimary
            )
        }
        Spacer(Modifier.height(10.dp))
        Label(
            text = profile.name,
            style = skin.body,
            color = if (focused) VideoclubColors.TextPrimary else VideoclubColors.TextSecondary,
            maxLines = 1
        )
    }
}
