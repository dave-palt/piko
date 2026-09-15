/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.videoQuality;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

import com.instagram.model.mediasize.VideoUrlImpl;

/**
 * In-app playback quality selector.
 *
 * IG picks the progressive playback URL via X/08iu.A00/A01 (prefer type==100, else
 * max-width; A00 = the chosen VideoUrlImpl, A01 = a list variant). We hook the
 * RESULT of those pickers and re-pick from the same list according to the user's
 * preference. The chosen object's URL field (A06) is read by the player, so we
 * can simply return a different element of the same list.
 *
 * Values: "" (default / stock), "highest", "720", "480", "360".
 */
public final class VideoQuality {

    private VideoQuality() {}

    // Field handles are app-lifetime stable — resolve once, not per pick.
    // Null until first successful resolve; readers fall back to a slow lookup.
    private static java.lang.reflect.Field dtoListField;
    private static java.lang.reflect.Field widthField;
    private static java.lang.reflect.Field typeField;
    private static java.lang.reflect.Field urlField;

    private static java.lang.reflect.Field field(
            java.lang.reflect.Field cached, Object instance, String name) {
        if (cached != null) return cached;
        try {
            java.lang.reflect.Field f = instance.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Exception e) {
            // Unresolvable on this build — caller treats as absent data.
            return null;
        }
    }

    // Sticky session preference: the user's last explicit ⋮ pick, expressed as the
    // (height, codec-efficiency) combo they chose — NOT a URL. Every subsequent
    // reel (including the current one) is matched to the closest available variant:
    // same height preferred, else nearest height, same codec preferred, hw-only.
    // null = no sticky pick (Default). Survives scrolling; cleared on app restart.
    private static volatile int stickyHeight = -1;
    private static volatile int stickyEff = -1;

    /** Records the user's ⋮ pick as the session-wide sticky preference. */
    public static void overrideFor(String chosenUrl) {
        if (chosenUrl == null || chosenUrl.isEmpty()) return;
        // Resolve the url's height/efficiency via CodecCapabilities through any
        // list context we have; store the combo. Called from the picker dialog,
        // which passes the chosen VideoData — see overrideCombo().
        Logger.printInfo(() -> "videoQuality sticky pick: " + shorten(chosenUrl));
    }

    /** Sticky pick expressed directly as height + codec info (picker knows both). */
    public static void overrideCombo(int height, CodecCapabilities.CodecInfo codec) {
        stickyHeight = height;
        stickyEff = codec == null ? -1 : codec.efficiency;
        Logger.printInfo(() -> "videoQuality sticky combo: h=" + height + " eff=" + stickyEff);
    }

    /** Clears the sticky pick ("Default" row). */
    public static void clearOverride() {
        stickyHeight = -1;
        stickyEff = -1;
    }

    public static boolean hasOverride() {
        return stickyHeight >= 0;
    }

    /**
     * Closest variant to the sticky combo: same height > nearest height (never
     * more than one rung above), then most similar codec efficiency. hw-only so
     * a sticky pick never lands the user on a software-decode rung.
     */
    private static Object applyOverride(java.util.List<?> variants) {
        if (stickyHeight < 0) return null;
        Object best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Object v : variants) {
            CodecCapabilities.CodecInfo codec = codecOf(v);
            if (codec == null || !codec.decodable || !codec.hardware) continue;
            int h = heightOf(v);
            if (h < 0) continue;
            // Resolution dominates; penalize stepping UP more than stepping down.
            int dH = Math.abs(h - stickyHeight) + (h > stickyHeight ? 2 : 0);
            int dE = stickyEff >= 0 ? Math.abs(codec.efficiency - stickyEff) : 0;
            int score = dH * 10 + dE;
            if (score < bestScore) {
                best = v;
                bestScore = score;
            }
        }
        return best;
    }

    // Only the single most-recent URL the hook handed the player — the marker must
    // identify ONE row per dialog, not everything ever chosen this session.
    private static volatile String lastChosenUrl;

    private static void rememberChosen(Object chosen) {
        String url = urlOf(chosen);
        if (url != null) lastChosenUrl = url;
    }

    /** True when this URL is what the player most recently received. */
    public static boolean isCurrentlyUsed(String url) {
        return url != null && url.equals(lastChosenUrl);
    }

    private static String urlOf(Object v) {
        try {
            java.lang.reflect.Field f = field(urlField, v, "A06");
            if (f == null) return null;
            urlField = f;
            Object o = f.get(v);
            return o instanceof String ? (String) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String shorten(String url) {
        int q = url.indexOf('?');
        String base = q > 0 ? url.substring(0, q) : url;
        return base.length() > 80 ? base.substring(0, 80) + "…" : base;
    }

    /** Returns true when a non-default quality override is active. */
    private static boolean enabled() {
        String mode = mode();
        return !mode.isEmpty() && !"default".equals(mode);
    }

    private static String mode() {
        try {
            String m = Pref.videoQualityMode();
            return m == null ? "" : m.trim().toLowerCase();
        } catch (Exception e) {
            Logger.printException(() -> "videoQuality mode read failed", e);
            return "";
        }
    }

    private static int targetWidth(String mode) {
        if ("highest".equals(mode)) return Integer.MAX_VALUE;
        if ("720".equals(mode)) return 720;
        if ("480".equals(mode)) return 480;
        if ("360".equals(mode)) return 360;
        return -1;
    }

    /** True for modes that participate in re-picking (everything except default). */
    private static boolean isKnownMode(String mode) {
        return "highest".equals(mode) || "best".equals(mode) || "lowest".equals(mode)
                || "720".equals(mode) || "480".equals(mode) || "360".equals(mode);
    }

    /**
     * Hook target: X/08iu.A00(LX/03oL;)Lcom/instagram/model/mediasize/VideoUrlImpl;
     * Receives the stock-picked VideoUrlImpl and the 03oL DTO whose A0S field is the
     * full variant list. Returns either the original (default mode / any failure) or
     * the re-picked variant.
     */
    public static Object pick(Object original, Object mediaDto) {
        try {
            Object result = pickInternal(original, mediaDto, null);
            rememberChosen(result);
            return result;
        } catch (Exception e) {
            Logger.printException(() -> "videoQuality pick failed", e);
            return original;
        }
    }

    private static Object pickInternal(Object original, Object mediaDto, Void unused) {
            if (original == null || mediaDto == null) {
                return original;
            }

            java.util.List<?> variants = variantListOf(mediaDto);
            if (variants == null || variants.isEmpty()) {
                return original;
            }

            // Per-reel pick wins over both stock and the global mode.
            Object override = applyOverride(variants);
            if (override != null) return override;

            if (!enabled()) {
                return original;
            }

            Object picked = pickFromList(variants);
            return picked == null ? original : picked;
    }

    /**
     * Hook target: X/08iu.A01(Ljava/util/List;)Lcom/instagram/model/mediasize/VideoUrlImpl;
     * Receives the stock-picked VideoUrlImpl and the list it was picked from.
     */
    public static Object pickFromList(Object original, Object list) {
        try {
            Object result = pickFromListInternal(original, list);
            rememberChosen(result);
            return result;
        } catch (Exception e) {
            Logger.printException(() -> "videoQuality pickFromList failed", e);
            return original;
        }
    }

    private static Object pickFromListInternal(Object original, Object list) {
            if (original == null || list == null || !(list instanceof java.util.List)) {
                return original;
            }

            java.util.List<?> variants = (java.util.List<?>) list;
            if (variants.isEmpty()) {
                return original;
            }

            // Per-reel pick wins over both stock and the global mode.
            Object override = applyOverride(variants);
            if (override != null) return override;

            if (!enabled()) {
                return original;
            }

            Object picked = pickFromList(variants);
            return picked == null ? original : picked;
    }

    private static java.util.List<?> variantListOf(Object mediaDto) {
        try {
            java.lang.reflect.Field f = field(dtoListField, mediaDto, "A0S");
            if (f == null) return null;
            dtoListField = f;
            Object v = f.get(mediaDto);
            if (v instanceof java.util.List) {
                return (java.util.List<?>) v;
            }
        } catch (Exception e) {
            Logger.printException(() -> "videoQuality A0S reflection failed", e);
        }
        return null;
    }

    /**
     * Re-implements the stock logic of 08iu.A00/A01 with a width cap/override:
     * - highest: prefer type 100/101/102, else the max-width element (stock behaviour),
     *   but ignoring the stock screen-width>480 gate.
     * - NNN: pick the max-width element whose width <= NNN; if none fits, take the
     *   smallest available (never worse than stock on small lists).
     */
    private static Object pickFromList(java.util.List<?> variants) {
        String mode = mode();

        // Dynamic device-aware modes: pick per media from what's actually available.
        if ("best".equals(mode) || "lowest".equals(mode)) {
            return dynamicPick(variants, "best".equals(mode));
        }

        int target = targetWidth(mode);
        if (target < 0) return null;

        Object best = null;
        int bestW = -1;

        // Pass 1: preferred types (mirrors stock preference for 100/101/102).
        for (Object v : variants) {
            Integer type = typeOf(v);
            if (type == null) continue;
            if (type == 100 || type == 101 || type == 102 || type == -2) {
                int w = widthOf(v);
                if (fits(w, target) && w > bestW) {
                    best = v;
                    bestW = w;
                }
            }
        }
        if (best != null) return best;

        // Pass 2: any type, best width that fits.
        for (Object v : variants) {
            int w = widthOf(v);
            if (w >= 0 && fits(w, target) && w > bestW) {
                best = v;
                bestW = w;
            }
        }
        if (best != null) return best;

        // Pass 3 (capped modes only): nothing fits the cap — take the smallest.
        if (target != Integer.MAX_VALUE) {
            int minW = Integer.MAX_VALUE;
            Object minV = null;
            for (Object v : variants) {
                int w = widthOf(v);
                if (w >= 0 && w < minW) {
                    minV = v;
                    minW = w;
                }
            }
            return minV;
        }
        return null;
    }

    private static boolean fits(int width, int target) {
        return target == Integer.MAX_VALUE || width <= target;
    }

    /**
     * Device-aware dynamic pick, recomputed per media:
     * - best = the highest-resolution HARDWARE-decodable variant (skip sw!/unsupported),
     *          efficiency as tie-break; this is the ★ rule with resolution maxed.
     * - lowest = the ★ rule itself: hw-decodable, lowest resolution, most efficient codec.
     */
    private static Object dynamicPick(java.util.List<?> variants, boolean highest) {
        Object best = null;
        int bestH = -1;
        int bestEff = 99;
        for (Object v : variants) {
            CodecCapabilities.CodecInfo codec = codecOf(v);
            if (codec == null || !codec.decodable || !codec.hardware) continue;
            int h = heightOf(v);
            if (h < 0) continue;
            int eff = codec.efficiency;
            boolean better;
            if (highest) {
                better = h > bestH || (h == bestH && eff < bestEff);
            } else {
                better = h < bestH || bestH < 0 || (h == bestH && eff < bestEff);
            }
            if (better) {
                best = v;
                bestH = h;
                bestEff = eff;
            }
        }
        return best;
    }

    private static CodecCapabilities.CodecInfo codecOf(Object v) {
        try {
            return CodecCapabilities.of(typeOf(v) == null ? 0 : typeOf(v), urlOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private static int heightOf(Object v) {
        try {
            java.lang.reflect.Field f = v.getClass().getDeclaredField("A00");
            f.setAccessible(true);
            return f.getInt(v);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int widthOf(Object v) {
        try {
            java.lang.reflect.Field f = field(widthField, v, "A02");
            if (f == null) return -1;
            widthField = f;
            return f.getInt(v);
        } catch (Exception e) {
            return -1;
        }
    }

    private static Integer typeOf(Object v) {
        try {
            java.lang.reflect.Field f = field(typeField, v, "A01");
            if (f == null) return null;
            typeField = f;
            return f.getInt(v);
        } catch (Exception e) {
            return null;
        }
    }
}
