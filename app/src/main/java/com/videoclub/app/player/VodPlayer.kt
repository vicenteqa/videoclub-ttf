package com.videoclub.app.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.videoclub.app.data.TrackChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient

/**
 * Why playback stopped, in the two flavours that call for different answers.
 *
 * A file this device cannot decode is permanent and the only useful response is a different copy;
 * a connection that dropped is temporary and the only useful response is to try again. Showing one
 * message for both — which is what "no se ha podido reproducir" was — sends the viewer looking for
 * the wrong remedy.
 */
data class PlaybackFailure(val code: String, val isFormatProblem: Boolean)

/** The audio track the player fell back to after the chosen one would not decode. */
data class AudioFallback(val from: String?, val to: String?)

/**
 * One film or one episode, played from a progressive HTTP file.
 *
 * Nothing here resembles the live-television player in the sibling project, and the reason is the
 * transport. A channel is one endless request that must be babysat with watchdogs; a film is a
 * 5–70 GB Matroska file on a server that answers `206 Partial Content` with `Accept-Ranges: bytes`.
 * That means seeking works, resuming works, and a failure is a real failure rather than something
 * to retry against a different feed.
 *
 * Track selection is left to ExoPlayer and to the controls. The catalogue's audio is AC3, E-AC3 and
 * occasionally DTS, and Matroska files here usually carry more than one track — letting the player
 * pick the one this device can actually decode is what keeps a phone from playing a film in silence.
 */
// `UnstableApi` is a Java annotation, so Kotlin's own `@OptIn` does not apply to it.
@OptIn(UnstableApi::class)
class VodPlayer(
    context: Context,
    httpClient: OkHttpClient,
    userAgent: String
) {

    private val _failure = MutableStateFlow<PlaybackFailure?>(null)
    val failure: StateFlow<PlaybackFailure?> = _failure.asStateFlow()

    private val _audioFallback = MutableStateFlow<AudioFallback?>(null)
    val audioFallback: StateFlow<AudioFallback?> = _audioFallback.asStateFlow()

    /** Audio tracks whose decoder has already failed on this file. Never retried. */
    private val refusedAudio = mutableSetOf<String>()

    /** The last non-empty track list. A fatal error clears [ExoPlayer.getCurrentTracks]. */
    private var knownTracks: Tracks = Tracks.EMPTY

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(
            DefaultRenderersFactory(context)
                .setMediaCodecSelector(DOLBY_VISION_TOLERANT)
                // If the chosen decoder refuses to start, try the next one rather than giving up on
                // the first refusal.
                .setEnableDecoderFallback(true)
                // The device's own decoders first, the bundled FFmpeg one behind them. `ON` places
                // the extension renderers after the `MediaCodec` ones, so a television box keeps
                // using its hardware DTS decoder and only a device that has none — every phone and
                // tablet, which ship neither DTS nor AC3 — falls through to software. Without this
                // the audio renderer is simply never enabled: no error, no track, silent film.
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultDataSource.Factory(
                    context,
                    OkHttpDataSource.Factory { request -> httpClient.newCall(request) }
                        .setUserAgent(userAgent)
                )
            )
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
                )
                .build()
        )
        // A remote has two convenient keys and a film is two hours long; ten back and thirty
        // forward is the pair that gets you past an advert break and back over a line of dialogue.
        .setSeekBackIncrementMs(SEEK_BACK_MS)
        .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (tracks.groups.isNotEmpty()) knownTracks = tracks
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "Playback failed: ${error.errorCodeName}", error)
                    if (retryWithoutRefusedAudio(error)) return
                    _failure.value = PlaybackFailure(
                        code = error.errorCodeName,
                        isFormatProblem = error.errorCode in FORMAT_ERRORS
                    )
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) _failure.value = null
                }
            })
        }

    fun open(url: String, startPositionMillis: Long, preferred: TrackChoice? = null) {
        _failure.value = null
        _audioFallback.value = null
        refusedAudio.clear()
        knownTracks = Tracks.EMPTY
        prefer(preferred)
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        if (startPositionMillis > 0) exoPlayer.seekTo(startPositionMillis)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
    }

    /**
     * Asks for the audio and the subtitles this viewer had last time, by language.
     *
     * A *preference* and not an override, which is the important part: the selector falls back to
     * whatever the file does have when the language is missing, so a series whose second season is
     * only dubbed still plays instead of coming up silent. Subtitles switched off are the one thing
     * asked for absolutely — that is a decision, not a preference, and a file that happens to carry
     * a Spanish subtitle track must not undo it.
     */
    private fun prefer(choice: TrackChoice?) {
        if (choice == null || choice.isEmpty) return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().apply {
            choice.audio?.let { setPreferredAudioLanguage(it) }
            when {
                choice.subtitlesOff -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                choice.subtitle != null -> {
                    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    setPreferredTextLanguage(choice.subtitle)
                }
            }
        }.build()
    }

    /**
     * What is actually playing right now, as languages.
     *
     * Read from the selection rather than from what was asked for, so it records what the viewer
     * ended up watching — including the track the fallback above had to choose for them, and
     * including the one the selector picked on its own when nobody chose anything.
     *
     * A file with no subtitle tracks at all reports null rather than "off": there was no choice to
     * make, and writing "off" would then follow the viewer to the next episode, which may have them.
     */
    fun chosen(): TrackChoice {
        val tracks = exoPlayer.currentTracks.takeIf { it.groups.isNotEmpty() } ?: knownTracks
        return TrackChoice(
            audio = selectedLanguage(tracks, C.TRACK_TYPE_AUDIO),
            subtitle = when {
                tracks.groups.none { it.type == C.TRACK_TYPE_TEXT } -> null
                else -> selectedLanguage(tracks, C.TRACK_TYPE_TEXT) ?: ""
            }
        )
    }

    private fun selectedLanguage(tracks: Tracks, type: Int): String? = tracks.groups
        .filter { it.type == type && it.isSelected }
        .flatMap { group -> (0 until group.length).map { group to it } }
        .firstOrNull { (group, index) -> group.isTrackSelected(index) }
        ?.let { (group, index) -> group.getTrackFormat(index).language }

    /**
     * Answers an audio decoder that refused a track by choosing a different track.
     *
     * This device has exactly one E-AC3 decoder, `c2.dolby.eac3.decoder`, and it throws
     * `CodecException: Error 0x80000000` on **Atmos** (JOC) streams while reporting
     * `format_supported=YES`. Plain E-AC3 5.1 on the same decoder plays. Since these files routinely
     * carry several audio tracks in different languages and encodings, the file is usually still
     * playable — just not with the track the selector picked first.
     *
     * Deliberately not the same remedy as a *video* failure: a bad video track means a different copy
     * of the file, a bad audio track means a different track in the same copy. Answering an audio
     * failure by re-downloading a 10 GB file in another encode would fail identically, which is
     * exactly what was measured on `4K - Cape Fear S01E01` before this existed.
     *
     * @return true when a different track was chosen and playback resumed, so no error is reported.
     */
    private fun retryWithoutRefusedAudio(error: PlaybackException): Boolean {
        val failed = (error as? ExoPlaybackException)
            ?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
            ?.rendererFormat
            ?.takeIf { MimeTypes.isAudio(it.sampleMimeType) }
            ?: return false

        refusedAudio += trackKey(failed)
        val alternative = knownTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).map { group to it } }
            .firstOrNull { (group, index) -> trackKey(group.getTrackFormat(index)) !in refusedAudio }
            ?: return false

        val (group, index) = alternative
        Log.w(TAG, "Audio track ${failed.label ?: failed.language} refused; trying ${
            group.getTrackFormat(index).label ?: group.getTrackFormat(index).language
        }")

        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
        _audioFallback.value = AudioFallback(
            from = describe(failed),
            to = describe(group.getTrackFormat(index))
        )

        // Back to where the viewer was, not to the beginning: the error arrives seconds in.
        val position = exoPlayer.currentPosition
        exoPlayer.prepare()
        exoPlayer.seekTo(position)
        return true
    }

    /** Stable across the re-prepare, unlike a track index. */
    private fun trackKey(format: Format): String =
        format.id ?: "${format.language}|${format.label}|${format.bitrate}"

    /** `Ingles DD+ 5.1 Atmos @ 768 kb/s`, or the language, or the codec — whatever the file gave. */
    private fun describe(format: Format): String? =
        format.label ?: format.language ?: format.codecs ?: format.sampleMimeType

    /** Where the viewer is, or null before the file has reported a duration. */
    fun position(): Pair<Long, Long>? {
        val duration = exoPlayer.duration
        if (duration == C.TIME_UNSET || duration <= 0) return null
        return exoPlayer.currentPosition to duration
    }

    fun pause() = exoPlayer.pause()

    fun release() = exoPlayer.release()

    private companion object {
        const val TAG = "VodPlayer"

        /**
         * Everything from "no decoder exists" to "the decoder choked", which is the set worth
         * answering with a different copy of the file.
         */
        val FORMAT_ERRORS = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

        /**
         * Plays a Dolby Vision track on a device that has no Dolby Vision decoder.
         *
         * A good part of this catalogue's 4K is Dolby Vision **profile 8.1**: one HEVC Main 10
         * stream whose base layer is ordinary HDR10, with the Dolby metadata riding alongside as an
         * RPU. Media3 reads the `dvvC` block in the Matroska header and labels the track
         * `video/dolby-vision`; a Pixel declares no such decoder — measured, there is not one on the
         * device — and playback stops on a file the phone can perfectly well decode if asked as
         * HEVC. So when nothing answers for Dolby Vision, the HEVC decoders are offered instead.
         *
         * The result is HDR10 rather than Dolby Vision, which is exactly what a device with no
         * Dolby Vision decoder was ever going to manage.
         */
        val DOLBY_VISION_TOLERANT = MediaCodecSelector { mimeType, secure, tunneling ->
            val decoders = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
            if (decoders.isEmpty() && mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
                MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, secure, tunneling)
            } else {
                decoders
            }
        }

        /**
         * Generous, because the files are enormous.
         *
         * A 4K remux in this catalogue runs at 20–70 Mbps, and one at 97 Mbps was measured. Half a
         * minute of buffer at those rates is real memory, but the alternative is a stall every time
         * the connection dips, and a stall in the middle of a film is far worse than one at the
         * start of it.
         */
        const val MIN_BUFFER_MS = 30_000
        const val MAX_BUFFER_MS = 90_000
        const val BUFFER_FOR_PLAYBACK_MS = 2_500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 6_000

        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
    }
}
