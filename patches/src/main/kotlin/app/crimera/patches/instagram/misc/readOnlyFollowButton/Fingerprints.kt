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

// Classic Litho/view wiring chokepoint (X/5b5, classes13). Every view-based
// follow button (home-feed inline_follow_button row header, and similar
// non-compose surfaces) funnels its click listener through A05's two
// 0es.A00(listener, view) attach calls. The injection swaps the listener
// argument (v11 at the :cond_97 site — the custom listener from field A00 or
// the default 5bJ follow action) for the extension's no-op OnClickListener.
// Uniqueness: this is the only method app-wide taking (LX/2ep;LX/8GC;
// UserSession;LX/5bH;LX/2eq;String;String;String;ZZZ)V — the 5bH param makes
// the full signature unique (verified by grep).
internal object ViewFollowButtonWiringFingerprint : Fingerprint(
    definingClass = "LX/5b5;",
    name = "A05",
    parameters = listOf(
        "LX/2ep;",
        "LX/8GC;",
        "Lcom/instagram/common/session/UserSession;",
        "LX/5bH;",
        "LX/2eq;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Z", "Z", "Z",
    ),
    returnType = "V",
)
