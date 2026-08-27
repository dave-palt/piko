/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.metaai

import app.morphe.patcher.Fingerprint

// DM inbox search bar (com.instagram.direct.inbox.feature.searchbar.ui.SearchBar).
// The Meta AI variant of the search bar is driven by the state object built
// from 64-bit MobileConfig params (Gbn.A01 / GAI.A03); its A08 boolean
// ("compose the Meta AI button/icon") is read in three composables. Note the
// flag-hook kills do NOT cover this surface: those flow through the integer
// flags in HookFlags, while this gate comes from MobileConfigUnsafeContext.
internal object SearchBarContentFingerprint : Fingerprint(
    strings = listOf("com.instagram.direct.inbox.feature.searchbar.ui.SearchBarContent (SearchBar.kt:119)"),
    returnType = "V",
)

internal object SearchBarIconFingerprint : Fingerprint(
    strings = listOf("com.instagram.direct.inbox.feature.searchbar.ui.SearchBarIcon (SearchBar.kt:212)"),
    returnType = "V",
)

internal object MetaAiCustomActionButtonFingerprint : Fingerprint(
    strings = listOf("com.instagram.direct.inbox.feature.searchbar.ui.MetaAiCustomActionButton (SearchBar.kt:464)"),
    returnType = "V",
)
