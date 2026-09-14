/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.videoQuality

import app.morphe.patcher.Fingerprint

internal object VideoQualityExtensionPickFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/instagram/patches/videoQuality/VideoQuality;",
    name = "pick",
)

internal object VideoQualityExtensionPickFromListFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/instagram/patches/videoQuality/VideoQuality;",
    name = "pickFromList",
)
