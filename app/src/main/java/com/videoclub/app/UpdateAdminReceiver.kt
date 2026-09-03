package com.videoclub.app

import android.app.admin.DeviceAdminReceiver

/**
 * Exists for one reason: being device owner requires a `DeviceAdminReceiver` to point at, and a
 * device is only made device owner through `adb shell dpm set-device-owner`, once, on a box with no
 * accounts on it yet — see DEPLOYMENT.md.
 *
 * No callback is overridden. This app locks nothing, wipes nothing and manages no other app; the
 * only capability being device owner buys it is a `PackageInstaller` session that can confirm
 * itself, which is what [Updater] needs to install a release with nobody in the room. See
 * `res/xml/device_admin.xml` for the policy set this declares, which is empty on purpose.
 */
class UpdateAdminReceiver : DeviceAdminReceiver()
