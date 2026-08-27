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
 * Runtime helper for the network-level Meta AI block. Injected at the two
 * central request funnels (REST 2tK.A01 and GraphQL 6mu.A00): swaps any
 * Meta-AI endpoint path / query name for a dead one so the request can
 * never reach the server, regardless of which UI surface triggered it.
 */
public class MetaAiBlock {
    private static final List<String> REST_PATHS = Arrays.asList(
            "direct_v2/ig_meta_ai_side_chat_send_contextual_query/",
            "cache/meta_ai_imagine");

    // Case-sensitive camel/snake tokens: lowercased contains("kai") would
    // false-positive on unrelated words inside query names.
    private static final List<String> GQL_MATCHES = Arrays.asList(
            "MetaAi",
            "MetaAI",
            "meta_ai",
            "AiAgent",
            "ai_agent",
            "SocialKai",
            "KaiInfo");

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

    /** Returns a dead query name when the given GraphQL op is Meta-AI. */
    public static String gqlName(String name) {
        if (name == null || !enabled()) return name;
        for (String m : GQL_MATCHES) {
            if (name.contains(m)) return "piko_meta_ai_blocked";
        }
        return name;
    }
}
