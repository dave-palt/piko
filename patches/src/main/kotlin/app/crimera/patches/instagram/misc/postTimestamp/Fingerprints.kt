/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.postTimestamp

import app.morphe.patcher.Fingerprint

// Reels caption component builder #1 (IG 435: X/Xu2). The public A0i method
// logs "is_reels_caption_expanded" right after reading the 6xB.A2j flag; the
// private A01 sibling renders the timestamp row (gated on A2j in stock).
internal object ReelsCaptionXu2Fingerprint : Fingerprint(
    strings = listOf("is_reels_caption_expanded"),
    custom = { methodDef, classDef ->
        methodDef.name == "A0i" &&
            classDef.fields.any { it.name == "A08" && it.type == "LX/1Mq;" }
    },
)

// Reels caption component builder #2 (IG 435: X/2SY). Same anchor string as
// Xu2; disambiguated by its 1j0 (ClipsMediaWrapper) field.
internal object ReelsCaption2SYFingerprint : Fingerprint(
    strings = listOf("is_reels_caption_expanded"),
    custom = { methodDef, classDef ->
        methodDef.name == "A0i" &&
            classDef.fields.any { it.name == "A01" && it.type == "LX/1j0;" }
    },
)

// Barcelona PostHeaderUsername composable (feed + reels post header). The
// if-nez v17 branch at the 135.A0M call picks between the flow-row (username
// + timestamp) and the inline path (username only when K4g.A04 is set).
internal object PostHeaderUsernameFingerprint : Fingerprint(
    strings = listOf("com.instagram.barcelona.feed.post.ui.PostHeaderUsername (PostHeaderUsername.kt:41)"),
    returnType = "V",
)
