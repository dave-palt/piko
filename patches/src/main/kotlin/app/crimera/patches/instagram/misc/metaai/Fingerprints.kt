/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.metaai

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

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

// DM inbox search overlay result row (classic RecyclerView binder, not compose).
// Reads the same 5Bu.A08 gate to decide whether to bind the "Ask Meta AI" row.
// Several bindView methods share the logging string, so also require the
// A08 gate read inside the implementation to pin the right class.
internal object MetaAiSearchRowFingerprint : Fingerprint(
    strings = listOf("Required value was null."),
    custom = { methodDef, _ ->
        methodDef.name == "bindView" &&
            methodDef.implementation?.instructions?.any { inst ->
                (inst as? ReferenceInstruction)
                    ?.reference
                    ?.toString()
                    ?.contains("LX/5Bu;->A08:Z") == true
            } == true
    },
)

// Central REST request funnel: every IG REST endpoint is built here, with the
// endpoint path in field A0G. Unique app-wide via its logging string.
internal object RestRequestFunnelFingerprint : Fingerprint(
    strings = listOf("Misconfigured cache information for request with path: %s"),
)

// Central GraphQL (Pando) request funnel: every GraphQL query object is built
// here. The query name / persisted-query hash arrive as String params.
// NOTE: deliberately NOT hooked — a rewritten query name is looked up in the
// PandoQueryExecutor schema registry (6lt.A00) and an unknown name crashes
// the app ("No PandoQueryExecutor configured for schema: null") instead of
// failing the request gracefully. Kept for documentation purposes.
internal object GraphQLRequestFunnelFingerprint : Fingerprint(
    parameters = listOf(
        "LX/ovR;",
        "Lcom/facebook/pando/PandoRealtimeInfoJNI;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/util/List;",
        "Ljava/util/Map;",
        "Ljava/util/Map;",
        "Lkotlin/jvm/functions/Function1;",
        "I",
        "Z",
    ),
    returnType = "Lcom/facebook/pando/PandoGraphQLRequest;",
)
