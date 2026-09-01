package com.videoclub.app.data

/**
 * The order in which a row's feeds should be tried on this device.
 *
 * ## Why a phone is not just a small television
 *
 * This supplier passes broadcast signal through untouched, so its FHD variants are **1080i
 * interlaced with E-AC3 audio** while its HD variants are 720p progressive with AAC. A television
 * decoder eats the first without blinking; phone decoders are built for camera and streaming
 * material, which is always progressive, and frequently render nothing at all. Measured on the
 * account in August 2026: `La 1 FHD` is H.264 High 1920x1080 `field_order=tt`, `La 1 HD` is H.264
 * Main 1280x720 progressive.
 *
 * So a handheld tries 720p first. This is a preference and not a filter: every feed curation found
 * stays in the chain, in its curated order, so a row that has no 720p variant still plays its FHD
 * one — and if that turns out to be black, the player's picture watchdog walks the chain anyway.
 *
 * The chain is reordered here, at the point of playback, rather than when the list is built. The
 * cached list on disk stays device-neutral, which means a row means the same thing everywhere and
 * curation stays one table instead of two.
 */
fun Channel.feedsFor(profile: DeviceProfile): List<Feed> = when (profile) {
    DeviceProfile.Tv -> feeds
    // A stable sort, so within each of the two groups the curated order survives untouched.
    DeviceProfile.Handheld -> feeds.sortedBy { feed ->
        if (feed.height == HANDHELD_HEIGHT) 0 else 1
    }
}

/** 720p: the highest resolution this supplier serves progressively. */
const val HANDHELD_HEIGHT = 720
