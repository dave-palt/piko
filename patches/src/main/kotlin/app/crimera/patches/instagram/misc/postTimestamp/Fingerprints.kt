/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.postTimestamp

import app.morphe.patcher.Fingerprint

// Reels caption component builder #1 (IG 435: X/Xu2; 439: X/04LW, classes5).
// The public builder method logs "is_reels_caption_expanded" right after
// reading the caption-expanded flag; the private render sibling renders the
// timestamp row (gated on that flag in stock).
// 435: method A0i, fields 1Mq/1j0. 439: method A0o, field 02Bb (holder)
// and 00RR (clips media wrapper).
internal object ReelsCaptionXu2Fingerprint : Fingerprint(
    strings = listOf("is_reels_caption_expanded"),
    custom = { methodDef, classDef ->
        methodDef.name == "A0o" &&
            classDef.fields.any { it.name == "A00" && it.type == "LX/02Bb;" }
    },
)

// Reels caption component builder #2 (IG 435: X/2SY; 439: X/0TXO, classes15).
// Same anchor string; disambiguated by its clips-media-wrapper field
// (435: 1j0, 439: 02Ep).
internal object ReelsCaption2SYFingerprint : Fingerprint(
    strings = listOf("is_reels_caption_expanded"),
    custom = { methodDef, classDef ->
        methodDef.name == "A0o" &&
            classDef.fields.any { it.name == "A08" && it.type == "LX/02Ep;" }
    },
)

// Classic (Litho) home-feed header subtitle-list builder (IG 435: X/7cq.A07,
// the row_feed_profile_header secondary/tertiary label contents). Unique
// app-wide log anchor; the method builds the 7lV subtitle-entry list that
// decides what shows beside the username (music attribution, timestamp, ...).
internal object FeedHeaderSubtitleListFingerprint : Fingerprint(
    strings = listOf("MediaHeaderInvalidUiState"),
    custom = { methodDef, _ ->
        methodDef.name == "A07" &&
            methodDef.parameterTypes.firstOrNull() == "Landroid/content/Context;"
    },
)

// Barcelona PostHeaderUsername composable (feed + reels post header). The
// branch after the layout-picker call picks between the flow-row (username
// + timestamp) and the inline path (username only).
// 435: PostHeaderUsername.kt:41; 439: PostHeaderUsername.kt:44.
internal object PostHeaderUsernameFingerprint : Fingerprint(
    strings = listOf("com.instagram.barcelona.feed.post.ui.PostHeaderUsername (PostHeaderUsername.kt:44)"),
    returnType = "V",
)
