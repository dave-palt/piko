/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 section 7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.metaai;

import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.Pref;

import java.util.Arrays;
import java.util.List;

/**
 * Runtime helper for the network-level Meta AI block. Injected at the central
 * REST request funnel (2tK.A01): swaps any Meta-AI endpoint path for a dead
 * one so the request can never reach the server, regardless of which UI
 * surface triggered it. (The GraphQL funnel 6mu.A00 is NOT hooked — see
 * Fingerprints.kt for why.)
 */
public class MetaAiBlock {
    private static final List<String> REST_PATHS = Arrays.asList(
            "direct_v2/ig_meta_ai_side_chat_send_contextual_query/",
            "cache/meta_ai_imagine");

    // Evaluated on every call so the settings toggle takes effect at once.
    private static boolean enabled() {
        return Pref.disableMetaAi() && SettingsStatus.disableMetaAi;
    }

    /** Returns a dead path when the given REST endpoint path is Meta-AI. */
    public static String restPath(String path) {
        if (path == null || !enabled()) return path;
        for (String m : REST_PATHS) {
            if (path.contains(m)) return "piko_meta_ai_blocked";
        }
        return path;
    }
}
