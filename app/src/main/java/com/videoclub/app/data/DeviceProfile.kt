package com.videoclub.app.data

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.core.content.getSystemService

/**
 * How the person in front of the app points at things.
 *
 * This is deliberately a two-way split rather than phone/tablet/television, because it answers one
 * question only: is there a D-pad and a ten-foot viewing distance, or a finger and arm's length?
 * Everything that varies by *size* — how many posters fit in a row, portrait or landscape — is a
 * question about the current window, not about the device, and is answered from the measured width
 * where it is needed. A tablet is a large phone for this purpose, and a phone in a desktop window
 * is a phone.
 */
enum class DeviceProfile { Tv, Handheld }

/**
 * Reads the device once, at startup.
 *
 * Three signals rather than one, because cheap Android TV boxes routinely ship a phone ROM and
 * report neither the television UI mode nor the leanback feature. Having no touchscreen is the one
 * thing none of them lie about.
 */
fun detectDeviceProfile(context: Context): DeviceProfile {
    val uiMode = context.getSystemService<UiModeManager>()?.currentModeType
    val isTelevision = uiMode == Configuration.UI_MODE_TYPE_TELEVISION
    val hasLeanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val hasTouchscreen = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    return if (isTelevision || hasLeanback || !hasTouchscreen) DeviceProfile.Tv else DeviceProfile.Handheld
}
