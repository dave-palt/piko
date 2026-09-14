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

    /**
     * Hook target: X/08iu.A00(LX/03oL;)Lcom/instagram/model/mediasize/VideoUrlImpl;
     * Receives the stock-picked VideoUrlImpl and the 03oL DTO whose A0S field is the
     * full variant list. Returns either the original (default mode / any failure) or
     * the re-picked variant.
     */
    public static Object pick(Object original, Object mediaDto) {
        try {
            if (original == null || mediaDto == null || !enabled()) {
                return original;
            }

            java.util.List<?> variants = variantListOf(mediaDto);
            if (variants == null || variants.isEmpty()) {
                return original;
            }

            Object picked = pickFromList(variants);
            return picked == null ? original : picked;
        } catch (Exception e) {
            Logger.printException(() -> "videoQuality pick failed", e);
            return original;
        }
    }

    /**
     * Hook target: X/08iu.A01(Ljava/util/List;)Lcom/instagram/model/mediasize/VideoUrlImpl;
     * Receives the stock-picked VideoUrlImpl and the list it was picked from.
     */
    public static Object pickFromList(Object original, Object list) {
        try {
            if (original == null || list == null || !(list instanceof java.util.List) || !enabled()) {
                return original;
            }

            java.util.List<?> variants = (java.util.List<?>) list;
            if (variants.isEmpty()) {
                return original;
            }

            Object picked = pickFromList(variants);
            return picked == null ? original : picked;
        } catch (Exception e) {
            Logger.printException(() -> "videoQuality pickFromList failed", e);
            return original;
        }
    }

    private static java.util.List<?> variantListOf(Object mediaDto) {
        try {
            java.lang.reflect.Field f = mediaDto.getClass().getDeclaredField("A0S");
            f.setAccessible(true);
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

    private static int widthOf(Object v) {
        try {
            java.lang.reflect.Field f = v.getClass().getDeclaredField("A02");
            f.setAccessible(true);
            return f.getInt(v);
        } catch (Exception e) {
            return -1;
        }
    }

    private static Integer typeOf(Object v) {
        try {
            java.lang.reflect.Field f = v.getClass().getDeclaredField("A01");
            f.setAccessible(true);
            return f.getInt(v);
        } catch (Exception e) {
            return null;
        }
    }
}
