/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.readOnlyFollowButton

import app.morphe.patcher.Fingerprint

// Barcelona FollowButton composable (feed + reels post header, FollowButton.kt:58).
// A01's p4 is the onClick Function0; the intrinsics null-check right after the
// prologue move is the injection anchor.
internal object FollowButtonFingerprint : Fingerprint(
    strings = listOf("com.instagram.barcelona.common.ui.button.FollowButton (FollowButton.kt:58)"),
    returnType = "V",
)

// IGDS FollowButtonComponent composable (IgdsPostHeader.kt:216).
// A02's p2 onClick (v10) is reassigned by the composer memo path, so it must
// be swapped at the consumption site (the wide static call consuming the
// Function0).
internal object IgdsFollowButtonComponentFingerprint : Fingerprint(
    strings = listOf("com.instagram.compose.igds.components.postheader.FollowButtonComponent (IgdsPostHeader.kt:216)"),
    returnType = "V",
)

// Classic view wiring chokepoint — the follow-button controller class.
// Every view-based follow button (home-feed inline_follow_button row header,
// and similar non-compose surfaces) funnels its click listener through this
// class's wiring method, whose 003D.A00(listener, view) attach calls are the
// injection sites.
//
// 435: X/5b5.A05 (11-param signature, 0es.A00 attach).
// 439: X/07eP.A05(02tG, 07YW, UserSession, 07hU, 01xd, String, String, String,
//      Z, Z, Z)V with 003D.A00 attaches (0JnG experiment / 07hW default follow
//      action / long-click listener). The full 11-param signature is unique
//      app-wide (verified by grep in 439; the only other matches are callers).
internal object ViewFollowButtonWiringFingerprint : Fingerprint(
    name = "A05",
    parameters = listOf(
        "LX/02tG;",
        "LX/07YW;",
        "Lcom/instagram/common/session/UserSession;",
        "LX/07hU;",
        "LX/01xd;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z",
        "Z",
        "Z",
    ),
    returnType = "V",
)
