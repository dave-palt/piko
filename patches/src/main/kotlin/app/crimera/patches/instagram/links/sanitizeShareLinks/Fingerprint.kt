/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.links.sanitizeShareLinks

import app.morphe.patcher.Fingerprint

// Share-URL request builders (local short-circuit targets, fork30/31 — dormant,
// debug-gated at runtime). Each anchor string is unique app-wide in 435
// (verified via strings classes*.dex); returnType deliberately unpinned so the
// fingerprints survive obfuscated-name churn across IG versions.
//
// X/MFy.A00(UserSession, Media, 6xB, Integer, String) — media permalink.
internal object MediaPermalinkRequestFingerprint : Fingerprint(
    strings = listOf("media/%s/permalink/"),
)

// X/MFy.A03(UserSession, Integer, String username, String mediaId, String) — story item.
internal object StoryItemUrlRequestFingerprint : Fingerprint(
    strings = listOf("third_party_sharing/%s/%s/get_story_item_url/"),
)

// X/KFb.A00(UserSession, Integer, String username, String) — profile.
internal object ProfileUrlRequestFingerprint : Fingerprint(
    strings = listOf("third_party_sharing/%s/get_profile_to_share_url/"),
)
