package com.videoclub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.videoclub.app.R
import com.videoclub.app.data.Channel
import com.videoclub.app.data.DeviceProfile
import com.videoclub.app.data.Programme
import com.videoclub.app.data.nowAndNext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The sizes the television section needs, which the videoclub's [Skin] has no business carrying.
 *
 * Everything here sits on top of moving video and is read across a room, so it is bigger than
 * anything on a poster wall — a channel row is 30sp where a shelf heading is 22sp. Keeping the two
 * scales apart is what stops one drifting into a half-scaled copy of the other, and it is why this
 * is a separate table rather than four more fields on [Skin].
 *
 * The palette, by contrast, is shared outright: contrast is contrast at any distance, and a second
 * set of greys would only make the app look like two apps.
 */
internal class LiveSkin(
    val channelRow: TextStyle,
    val channelRowSecondary: TextStyle,
    val infoTitle: TextStyle,
    val infoProgramme: TextStyle,
    val infoSecondary: TextStyle,
    val rowLogo: Dp,
    val infoLogo: Dp,
    /** The channel list never grows past this, however wide the screen is. */
    val panelMaxWidth: Dp,
    val screenPadding: Dp,
    /** The gutter the `Ahora 21:00` part of a guide line is given. */
    val programmeTimeWidth: Dp
) {
    companion object {
        private val Tv = LiveSkin(
            channelRow = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
            channelRowSecondary = TextStyle(fontSize = 20.sp),
            infoTitle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold),
            infoProgramme = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Medium),
            infoSecondary = TextStyle(fontSize = 22.sp),
            rowLogo = 56.dp,
            infoLogo = 72.dp,
            panelMaxWidth = 620.dp,
            screenPadding = 56.dp,
            programmeTimeWidth = 220.dp
        )

        /**
         * A phone held on its side is about 360dp tall, so a row has to fit six times over. Still a
         * good deal larger than a normal phone app: the audience is the same person.
         */
        private val Handheld = LiveSkin(
            channelRow = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
            channelRowSecondary = TextStyle(fontSize = 13.sp),
            infoTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
            infoProgramme = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            infoSecondary = TextStyle(fontSize = 13.sp),
            rowLogo = 34.dp,
            infoLogo = 42.dp,
            panelMaxWidth = 420.dp,
            screenPadding = 24.dp,
            programmeTimeWidth = 130.dp
        )

        fun of(profile: DeviceProfile): LiveSkin = when (profile) {
            DeviceProfile.Tv -> Tv
            DeviceProfile.Handheld -> Handheld
        }
    }
}

/**
 * The app's own near-black, made nearly opaque.
 *
 * A translucent panel looks better in a screenshot and loses to a bright picture underneath, which
 * is the only situation this panel is ever in.
 */
internal val LivePanel = VideoclubColors.Surface.copy(alpha = 0.95f)

/**
 * The channel list, over the picture rather than instead of it.
 *
 * Selection is driven by an index this composable is handed, not by Compose's focus system. On a
 * television that is the more predictable of the two: there is exactly one place the highlight can
 * be, holding the D-pad down cannot lose it to a neighbouring composable, and reopening the list
 * always lands where the viewer left it. It is the opposite choice from the poster rows next door,
 * and for the opposite reason — a row of posters has somewhere else to go sideways, a channel list
 * has not.
 *
 * On a phone the same list is scrolled with a finger and a row is tapped directly, so the highlight
 * is decoration there rather than a cursor — which is why [onSelect] carries the row's own index
 * instead of relying on the selection having been moved there first.
 */
@Composable
internal fun ChannelList(
    channels: List<Channel>,
    selectedIndex: Int,
    playingLabel: String?,
    guide: Map<Int, List<Programme>>,
    nowMillis: Long,
    skin: LiveSkin,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The panel's name for this household — see [ProviderConfig.houseName] — and which account it
     * is on. Both empty draws nothing.
     */
    house: String = "",
    accountUser: String = ""
) {
    val listState = rememberLazyListState()

    // The highlight sits a couple of rows down from the top and the list moves under it. Keeping
    // the cursor in one place is what stops a held key turning into a jumping screen.
    LaunchedEffect(selectedIndex) {
        listState.animateScrollToItem((selectedIndex - ROWS_ABOVE_CURSOR).coerceAtLeast(0))
    }

    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        // A fraction of the screen rather than a fixed width, or the panel is a comfortable third
        // of a television and wider than a phone.
        val panelWidth = minOf(skin.panelMaxWidth, maxWidth * PANEL_FRACTION)

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(panelWidth)
                .background(LivePanel)
                // A tap on the panel that lands between rows stops here. Whatever is behind the
                // list treats a tap as "put the list away", and the gap under the last channel is
                // not a place a viewer means that.
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            LazyColumn(
                state = listState,
                // `weight` rather than a bare `fillMaxWidth`: without it the list takes the whole
                // height of the column and pushes the footer off the screen, where nobody ever sees
                // it.
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 24.dp)
            ) {
                itemsIndexed(channels, key = { _, channel -> channel.label }) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        selected = index == selectedIndex,
                        playing = channel.label == playingLabel,
                        programme = guide[channel.feeds.first().streamId]
                            ?.nowAndNext(nowMillis)
                            ?.first,
                        skin = skin,
                        onClick = { onSelect(index) }
                    )
                }
            }

            if (house.isNotBlank() || accountUser.isNotBlank()) {
                AccountFooter(house = house, user = accountUser, skin = skin)
            }
        }
    }
}

/**
 * Which household this device belongs to and which account it is on, at the foot of the list.
 *
 * Diagnosing "nothing is showing" from another house comes down to two questions — is this the APK
 * I think I installed? is it on the account I think it is? — and the only way to answer them was a
 * cable and `adb`. Now it is "open the list and read me what is at the bottom", over the phone.
 *
 * The household comes from `BuildConfig` rather than from the hosted document, and that is the
 * point: it is the flavour's name, that is, **which APK** was installed, which is precisely what one
 * gets wrong when sideloading. Asking the document would answer the other question — whose account
 * has been downloaded — and the two together are what separate "I installed the wrong one" from
 * "the panel is pointing somewhere it should not". Never the URL: it carries the secret path, which
 * acts as the credential.
 *
 * In smaller type than a channel row, because it is not content: it is a plate with a serial number
 * on it. Pinned below the list rather than being its last row — somebody reads it out loud with the
 * television misbehaving, and asking them to scroll first is one more instruction — and it exists
 * only while the list is open, so it never covers the picture.
 *
 * Holding OK while the list is open — the D-pad equivalent of a long press, over exactly this line
 * — asks the server whether there is a release waiting; see [Container.checkForUpdate]. Nothing
 * about that shows up here: it either lands on Android's own install prompt or does nothing.
 */
@Composable
private fun AccountFooter(house: String, user: String, skin: LiveSkin) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .padding(top = 20.dp, bottom = 8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(VideoclubColors.TextSecondary.copy(alpha = 0.18f))
        )
        Spacer(Modifier.height(12.dp))
        Label(
            text = listOf(house, user).filter { it.isNotBlank() }.joinToString(" · "),
            style = skin.channelRowSecondary.copy(
                fontSize = skin.channelRowSecondary.fontSize * FOOTER_SCALE
            ),
            color = VideoclubColors.TextSecondary,
            maxLines = 1
        )
    }
}

/** Just enough to read as a plate rather than as one more row. */
private const val FOOTER_SCALE = 0.8f

@Composable
private fun ChannelRow(
    channel: Channel,
    selected: Boolean,
    playing: Boolean,
    programme: Programme?,
    skin: LiveSkin,
    onClick: () -> Unit
) {
    // The selected row inverts. Nothing on a television reads as clearly from a sofa as the app's
    // own paper colour with its own black on top of it.
    val background = if (selected) VideoclubColors.TextPrimary else Color.Transparent
    val primary = if (selected) VideoclubColors.Surface else VideoclubColors.TextPrimary
    val secondary = if (selected) VideoclubColors.Surface else VideoclubColors.TextSecondary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            // Consumes the touch, which is what keeps a tap on a row from also reaching the
            // zapping gesture layer behind the list.
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(Modifier.size(skin.rowLogo), contentAlignment = Alignment.Center) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.size(skin.rowLogo - 4.dp)
                )
            }
        }

        Spacer(Modifier.width(20.dp))

        Column(Modifier.weight(1f)) {
            Label(
                text = channel.label,
                style = skin.channelRow,
                color = primary,
                maxLines = 1
            )
            if (programme != null) {
                Spacer(Modifier.height(2.dp))
                Label(
                    text = programme.title,
                    style = skin.channelRowSecondary,
                    color = secondary,
                    maxLines = 1
                )
            }
        }

        // A quiet marker for the channel that is actually on air behind the list, so reopening it
        // never leaves any doubt about what is playing. In the app's red, which everywhere else in
        // the app means "this is the thing you chose".
        if (playing) {
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(width = 6.dp, height = 40.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(VideoclubColors.Accent)
            )
        }
    }
}

/**
 * The strip that appears for a few seconds after a zap: which channel this is, what is on, what is
 * next.
 *
 * It never waits for the guide — the channel name is drawn immediately and the programme lines fill
 * in when the request comes back, which is a beat later and while the picture is already playing.
 */
@Composable
internal fun LiveInfoBar(
    channel: Channel,
    programmes: List<Programme>,
    nowMillis: Long,
    /** `Full HD`, or null until the decoder has reported a picture. */
    quality: String?,
    skin: LiveSkin,
    modifier: Modifier = Modifier
) {
    val (now, next) = programmes.nowAndNext(nowMillis)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, LivePanel)))
            .padding(
                start = skin.screenPadding,
                end = skin.screenPadding,
                top = skin.screenPadding + 8.dp,
                bottom = skin.screenPadding
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (channel.logoUrl != null) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.size(skin.infoLogo)
                )
                Spacer(Modifier.width(24.dp))
            }
            Label(text = channel.label, style = skin.infoTitle, maxLines = 1)
            // Appears a beat after the name, when the decoder knows. Drawn as an aside rather than
            // reserved for with a placeholder: an empty gap beside the channel name reads as
            // something missing, and this is a detail nobody is waiting for.
            if (quality != null) {
                Spacer(Modifier.width(20.dp))
                Label(
                    text = quality,
                    style = skin.infoSecondary,
                    color = VideoclubColors.TextSecondary
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (now == null && next == null) {
            Label(
                text = stringResource(R.string.live_no_guide),
                style = skin.infoSecondary,
                color = VideoclubColors.TextSecondary
            )
            return@Column
        }

        now?.let { programme ->
            ProgrammeLine(
                label = stringResource(R.string.live_now),
                programme = programme,
                style = skin.infoProgramme,
                color = VideoclubColors.TextPrimary,
                timeWidth = skin.programmeTimeWidth
            )
        }
        next?.let { programme ->
            Spacer(Modifier.height(6.dp))
            ProgrammeLine(
                label = stringResource(R.string.live_next),
                programme = programme,
                style = skin.infoSecondary,
                color = VideoclubColors.TextSecondary,
                timeWidth = skin.programmeTimeWidth
            )
        }
    }
}

@Composable
private fun ProgrammeLine(
    label: String,
    programme: Programme,
    style: TextStyle,
    color: Color,
    timeWidth: Dp
) {
    Row {
        Label(
            text = "$label  ${formatClock(programme.startMillis)}",
            style = style,
            color = VideoclubColors.TextSecondary,
            maxLines = 1,
            modifier = Modifier.width(timeWidth)
        )
        Label(text = programme.title, style = style, color = color, maxLines = 1)
    }
}

/**
 * `SimpleDateFormat` rather than `java.time`, so the app runs on an old television box without
 * dragging in core-library desugaring for one clock face. It is not thread-safe, hence the thread
 * local.
 */
private val clockFormat = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}

private fun formatClock(millis: Long): String = clockFormat.get()!!.format(Date(millis))

private const val PANEL_FRACTION = 0.62f
private const val ROWS_ABOVE_CURSOR = 2
