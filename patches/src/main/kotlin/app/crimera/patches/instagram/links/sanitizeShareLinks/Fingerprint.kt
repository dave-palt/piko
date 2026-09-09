/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.links.sanitizeShareLinks

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.Opcode

internal val TARGET_STRING_ARRAY =
    arrayOf(
        "XDTPermalinkResponse",
        "profile_to_share_url",
    )

internal object PermalinkResponseJsonParserFingerprint : Fingerprint(
    strings = listOf(TARGET_STRING_ARRAY[0]),
    custom = { methodDef, _ ->
        methodDef.name.lowercase().contains("parsefromjson") &&
            methodDef.implementation
                ?.instructions
                ?.filter { it.opcode == Opcode.CONST_STRING }!!
                .size < 3
    },
)

internal object ProfileUrlResponseJsonParserFingerprint : Fingerprint(
    strings = listOf(TARGET_STRING_ARRAY[1]),
    custom = { methodDef, _ ->
        methodDef.name.lowercase().contains("parsefromjson")
    },
)

internal object StoryUrlResponseImplFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    definingClass = "Lcom/instagram/request/StoryItemUrlResponseImpl;",
)

internal object LiveUrlResponseImplFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    definingClass = "Lcom/instagram/request/LiveItemLinkUrlResponseImpl;",
)

// Share-URL request builders (local short-circuit targets). Each anchor
// string is unique app-wide in 435 (verified via strings classes*.dex).
// X/MFy.A00(UserSession, Media, 6xB, Integer, String) — media permalink.
internal object MediaPermalinkRequestFingerprint : Fingerprint(
    strings = listOf("media/%s/permalink/"),
    returnType = "LX/2Hd;",
)

// X/MFy.A03(UserSession, Integer, String username, String mediaId, String) — story item.
internal object StoryItemUrlRequestFingerprint : Fingerprint(
    strings = listOf("third_party_sharing/%s/%s/get_story_item_url/"),
    returnType = "LX/2Hd;",
)

// X/KFb.A00(UserSession, Integer, String username, String) — profile.
internal object ProfileUrlRequestFingerprint : Fingerprint(
    strings = listOf("third_party_sharing/%s/get_profile_to_share_url/"),
    returnType = "LX/2Hd;",
)
