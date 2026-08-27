/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.readOnlyFollowButton

import app.morphe.patcher.Fingerprint

// Barcelona FollowButton composable (feed + reels post header, FollowButton.kt:58).
// A01's p4 is the onClick Function0; the 3l9.A0R null-check right after the
// prologue move is the injection anchor.
internal object FollowButtonFingerprint : Fingerprint(
    strings = listOf("com.instagram.barcelona.common.ui.button.FollowButton (FollowButton.kt:58)"),
    returnType = "V",
)

// IGDS FollowButtonComponent composable (IgdsPostHeader.kt:216).
// A02's p2 onClick (v10) is reassigned by the composer memo path, so it must
// be swapped at the consumption site (the D4K.A01 call).
internal object IgdsFollowButtonComponentFingerprint : Fingerprint(
    strings = listOf("com.instagram.compose.igds.components.postheader.FollowButtonComponent (IgdsPostHeader.kt:216)"),
    returnType = "V",
)
