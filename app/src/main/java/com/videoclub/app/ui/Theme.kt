package com.videoclub.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videoclub.app.data.DeviceProfile

/**
 * Near-black, one red, and two greys.
 *
 * A wall of film posters is already the most colourful thing on the screen; anything the app adds
 * competes with it. The red exists to say "this is the thing you pressed" and nothing else.
 */
object VideoclubColors {
    val Surface = Color(0xFF0B0B0F)
    val SurfaceElevated = Color(0xFF15151C)
    val Accent = Color(0xFFD81E33)
    val TextPrimary = Color(0xFFF2F2F4)
    val TextSecondary = Color(0xFF9A9AA6)
    /** A chip that is on screen but cannot be pressed. Dim enough to read as off, not as missing. */
    val TextDisabled = Color(0xFF4A4A56)
    val PosterPlaceholder = Color(0xFF1D1D26)

    /**
     * One colour per person, and the only place the palette above is broken on purpose.
     *
     * A letter alone is a weak thing to tell two people apart with across a room — `L` and `E` are
     * the same red circle until you are close enough to read them. The colour is what carries at
     * three metres, and the letter confirms it up close.
     *
     * Picked by [avatarColor] from the profile's id rather than at random, because a circle that is
     * a different colour on Tuesday is worse than no colour at all. The first person keeps the app's
     * own red, so nothing changes for a household that never opens the settings.
     */
    private val Avatars = listOf(
        Accent,
        Color(0xFF2F6FA8),
        Color(0xFF3D8B5F),
        Color(0xFF8A55B8),
        Color(0xFFC1762A),
        Color(0xFF3E7F86)
    )

    fun avatarColor(profileId: Int): Color =
        Avatars[((profileId % Avatars.size) + Avatars.size) % Avatars.size]
}

/**
 * Every size that changes between a television across the room and a phone in a hand.
 *
 * Two inputs decide it: whether there is a D-pad ([DeviceProfile]) and how wide the window actually
 * is. The first sets the viewing distance and therefore the type scale; the second sets how many
 * posters fit, which is a question about the window and not about the hardware — a tablet in split
 * screen is a phone for this purpose.
 */
data class Skin(
    val screenPadding: Dp,
    val rowGap: Dp,
    val posterGap: Dp,
    val posterWidth: Dp,
    /** Side of the round profile chip in the tab strip: a touch target here, a legible dot there. */
    val chipSize: Dp,
    val heroFraction: Float,
    val cornerRadius: Dp,
    val focusScale: Float,
    val heroTitle: TextStyle,
    val sectionTitle: TextStyle,
    val body: TextStyle,
    val caption: TextStyle,
    val button: TextStyle
) {
    /** Posters are 2:3 — every TMDB poster in this catalogue is 600×900. */
    val posterHeight: Dp get() = posterWidth * 3 / 2
}

private val LocalSkinInternal: ProvidableCompositionLocal<Skin> =
    staticCompositionLocalOf { error("No Skin: wrap the tree in VideoclubTheme") }

val LocalSkin: ProvidableCompositionLocal<Skin> get() = LocalSkinInternal

@Composable
fun VideoclubTheme(
    profile: DeviceProfile,
    widthDp: Int,
    content: @Composable () -> Unit
) {
    val skin = skinFor(profile, widthDp)
    CompositionLocalProvider(LocalSkinInternal provides skin) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                background = VideoclubColors.Surface,
                surface = VideoclubColors.Surface,
                primary = VideoclubColors.Accent,
                onBackground = VideoclubColors.TextPrimary,
                onSurface = VideoclubColors.TextPrimary
            ),
            content = content
        )
    }
}

/**
 * Three sizes, chosen by measurement rather than by device class.
 *
 * The television numbers are the ten-foot ones: 20sp is the floor at which a subtitle is readable
 * from a sofa, and the posters are wide enough that seven fit across a 1080p row — any more and
 * the artwork stops being legible, which defeats the point of a poster wall.
 */
private fun skinFor(profile: DeviceProfile, widthDp: Int): Skin = when {
    profile == DeviceProfile.Tv -> Skin(
        screenPadding = 48.dp,
        rowGap = 36.dp,
        posterGap = 16.dp,
        posterWidth = 150.dp,
        chipSize = 44.dp,
        heroFraction = 0.62f,
        cornerRadius = 8.dp,
        focusScale = 1.14f,
        heroTitle = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold),
        sectionTitle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        body = TextStyle(fontSize = 20.sp, lineHeight = 28.sp),
        caption = TextStyle(fontSize = 17.sp),
        button = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    )

    widthDp >= TABLET_WIDTH_DP -> Skin(
        screenPadding = 28.dp,
        rowGap = 28.dp,
        posterGap = 14.dp,
        posterWidth = 140.dp,
        chipSize = 38.dp,
        heroFraction = 0.52f,
        cornerRadius = 10.dp,
        focusScale = 1.06f,
        heroTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
        sectionTitle = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
        body = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
        caption = TextStyle(fontSize = 14.sp),
        button = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    )

    else -> Skin(
        screenPadding = 16.dp,
        rowGap = 22.dp,
        posterGap = 10.dp,
        posterWidth = 108.dp,
        chipSize = 34.dp,
        heroFraction = 0.58f,
        cornerRadius = 8.dp,
        focusScale = 1.04f,
        heroTitle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold),
        sectionTitle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        caption = TextStyle(fontSize = 12.sp),
        button = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    )
}

/** Below this a window is a phone, whatever the device is called. */
private const val TABLET_WIDTH_DP = 600

/** Shorthand for the app's own text colour, which is the one every label wants. */
@Composable
fun Label(
    text: String,
    style: TextStyle,
    color: Color = VideoclubColors.TextPrimary,
    maxLines: Int = Int.MAX_VALUE,
    /** Raise it to reserve the space a shorter text would not use, so a grid of them lines up. */
    minLines: Int = 1,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) = Text(
    text = text,
    style = style,
    color = color,
    maxLines = maxLines,
    minLines = minLines,
    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    modifier = modifier
)
