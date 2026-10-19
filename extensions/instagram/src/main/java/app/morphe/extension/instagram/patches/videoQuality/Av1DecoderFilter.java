/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.videoQuality;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Forces AV1 video playback onto SOFTWARE decoders (dav1d/gav1).
 *
 * 2026-10: Instagram serves reels as AV1-only DASH ladders. Some hardware AV1
 * decoders (notably Qualcomm c2.qti.av1.decoder) mishandle seeks/flushes on
 * these streams — hundreds of "Skipping empty output buffer" drops per scrub,
 * render FPS collapse, stale frames while buffered. Dropping hardware AV1
 * entries from the codec-selector list makes the player fall back to the
 * platform software decoder, which seeks correctly.
 *
 * Hook target: the codec selector's Bp8(mime, Z, Z)List (X/00PC on 439), whose
 * return list elements are decoder-info wrappers carrying the codec name in a
 * String field (A06 on 439 — resolved by shape at runtime, so field renames
 * cannot break this). Fail-open everywhere: unknown names are never dropped,
 * an empty result returns the original list, any exception returns the
 * original list.
 */
public final class Av1DecoderFilter {

    private Av1DecoderFilter() {}

    private static final String AV1_MIME = "video/av01";

    // Codec-name field on the decoder-info wrapper — resolved once by value
    // shape (String, no '/', contains "av1"), cached for the process lifetime.
    private static volatile Field nameField;

    private static volatile boolean loggedOnce;

    public static Object filter(String mime, Object list) {
        try {
            if (!AV1_MIME.equals(mime) || !(list instanceof List)) {
                return list;
            }
            if (!Pref.swAv1Decoding()) {
                return list;
            }
            List<?> original = (List<?>) list;
            if (original.isEmpty()) {
                return list;
            }

            List<Object> kept = new ArrayList<>();
            List<Object> dropped = new ArrayList<>();
            for (Object info : original) {
                String name = codecNameOf(info);
                // Unknown name = never drop (fail-open).
                if (name == null || isSoftwareCodec(name)) {
                    kept.add(info);
                } else {
                    dropped.add(info);
                }
            }
            if (dropped.isEmpty() || kept.isEmpty()) {
                return list;
            }
            final String droppedNames = namesOf(dropped);
            final String keptNames = namesOf(kept);
            if (!loggedOnce) {
                loggedOnce = true;
                Logger.printInfo(() -> "av1 sw-decode: dropped=[" + droppedNames
                        + "] kept=[" + keptNames + "]");
            }
            return kept;
        } catch (Exception e) {
            Logger.printException(() -> "av1 sw-decode filter failed", e);
            return list;
        }
    }

    /**
     * Software codec detection: AOSP prefixes, explicit .sw. markers, and the
     * software AV1 decoder PROJECT NAMES (dav1d, gav1) — the latter catches
     * IG's bundled "meta.dav1d.av1.decoder", which IS software despite the
     * non-AOSP prefix (emulator-verified: it appears inside the selector list).
     */
    private static boolean isSoftwareCodec(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("c2.android.") || n.startsWith("omx.google.")
                || n.contains(".sw.") || n.contains("dav1d") || n.contains("gav1");
    }

    private static String namesOf(List<Object> infos) {
        StringBuilder sb = new StringBuilder();
        for (Object info : infos) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(codecNameOf(info));
        }
        return sb.toString();
    }

    /**
     * Reads the codec-name String off a decoder-info wrapper. The field name is
     * obfuscated and rotates between versions, so resolve by VALUE shape once:
     * a String field whose value has no '/', contains '.', and mentions av1 —
     * codec names ("c2.qti.av1.decoder") qualify, mime types ("video/av01")
     * don't. Cached; null when unresolvable (caller keeps the entry).
     */
    private static String codecNameOf(Object info) {
        if (info == null) return null;
        try {
            Field cached = nameField;
            if (cached == null) {
                for (Field f : info.getClass().getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    f.setAccessible(true);
                    Object v = f.get(info);
                    if (v instanceof String) {
                        String s = (String) v;
                        if (s.indexOf('/') < 0 && s.indexOf('.') >= 0
                                && s.toLowerCase(java.util.Locale.ROOT).contains("av1")) {
                            nameField = f;
                            cached = f;
                            break;
                        }
                    }
                }
                if (cached == null) return null;
            }
            Object v = cached.get(info);
            return v instanceof String ? (String) v : null;
        } catch (Exception e) {
            return null;
        }
    }
}
