/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.instagram.patches.spoiler;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * Spoiler shield: hides feed/reel media that matches user/hashtag/word rules while it is
 * younger than a global age limit, behind Instagram's own blurred media-overlay cover.
 *
 * Injection point: the tail of {@code com.instagram.feed.media.Media}'s media-overlay
 * payload getter (the only no-arg method on Media returning MediaOverlayPayloadSchemaIntf).
 * When the stock payload is null (no server cover) and our rules match, we fabricate an
 * EARLY_ACCESS-style payload carrying the spoiler reason as the cover text. Stock server
 * covers always win; on any failure we fall back to the stock (null) payload.
 */
@SuppressWarnings("unused")
public final class SpoilerShield {

    private static volatile Method takenAtMethod;
    private static volatile boolean takenAtProbed;
    private static int callCount;

    /** Per-Media verdict cache: A1F/A1Z gates and the payload getter all ask the same question. */
    private static final Map<Object, Boolean> verdictCache =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    /** Fabricated-title token -> media thumbnail URL, consumed by the cover-image hook. */
    private static final Map<String, String> pendingCoverUrls =
            Collections.synchronizedMap(new java.util.HashMap<String, String>());

    /** Fabricated-title token -> human-readable reason, consumed by the cover-title hook. */
    private static final Map<String, String> pendingTitles =
            Collections.synchronizedMap(new java.util.HashMap<String, String>());

    private static long tokenSeq;


    private SpoilerShield() {
    }

    /**
     * Injection point. Takes the stock payload and the Media object; returns the payload
     * that should be used (fabricated spoiler cover, the stock one, or null).
     */
    public static Object getMediaOverlayPayload(Object stockPayload, Object media) {
        try {
            callCount++;
            if (callCount == 1 || callCount % 25 == 0) {
                Logger.printInfo(() -> "SpoilerShield call #" + callCount
                        + " stock=" + (stockPayload == null ? "null" : stockPayload.getClass().getSimpleName())
                        + " media=" + (media == null ? "null" : media.getClass().getName()));
            }
            if (stockPayload != null) return stockPayload;
            if (media == null) return null;
            if (!Pref.spoilerShield()) return null;

            String reason = matchReason(media);
            if (reason == null) return null;

            Object payload = fabricatePayload(reason, thumbnailUrlOf(media));
            if (payload != null) {
                Logger.printInfo(() -> "SpoilerShield covering media: " + reason);
            }
            return payload;
        } catch (Throwable t) {
            Logger.printException(() -> "SpoilerShield getMediaOverlayPayload failed", t);
            return stockPayload;
        }
    }

    /**
     * Injection point B: the tree-eligibility gates inside the cover builder. The builder
     * only consults the media payload when these return true; on a normal post they are
     * false, so a fabricated payload alone never renders. Forcing true for medias our
     * rules match makes the builder take the media path and consume the payload.
     */
    public static boolean forceCoverEligibility(boolean stock, Object media) {
        try {
            if (stock) return true;
            if (media == null) return false;
            if (!Pref.spoilerShield()) return false;

            Boolean cached = verdictCache.get(media);
            if (cached != null) return cached;

            boolean verdict = matchReason(media) != null;
            verdictCache.put(media, verdict);
            return verdict;
        } catch (Throwable t) {
            Logger.printException(() -> "SpoilerShield forceCoverEligibility failed", t);
            return stock;
        }
    }

    /**
     * Injection point C: the cover-config tail, right before the blur ImageUrl is stored.
     * Stock leaves it null for normal posts (no server early-access candidate, no
     * early-access URL template), and the binder paints nothing without it. We substitute
     * a SimpleImageUrl over the media's own thumbnail — the binder then blurs it
     * client-side (mini preview blur + dim color filter).
     */
    public static Object coverImage(Object stockImage, Object titleToken) {
        try {
            if (stockImage == null && titleToken instanceof String
                    && ((String) titleToken).startsWith("piko-spoiler:")) {
                Logger.printInfo(() -> "SpoilerShield coverImage invoked, token=" + titleToken
                        + " stashed=" + pendingCoverUrls.size());
            }
            if (stockImage != null) return stockImage;
            if (!(titleToken instanceof String)) return null;

            String url = pendingCoverUrls.remove(titleToken);
            if (url == null) return null;

            final String u = url;
            Logger.printInfo(() -> "SpoilerShield supplying cover image: " + u);
            return Class.forName("com.instagram.common.typedurl.SimpleImageUrl")
                    .getConstructor(String.class)
                    .newInstance(url);
        } catch (Throwable t) {
            Logger.printException(() -> "SpoilerShield coverImage failed", t);
            return stockImage;
        }
    }

    /**
     * Injection point D: the feed row controller's cover-path entry gates. The
     * controller skips the whole cover block for normal posts (both flags false —
     * stock only covers server-flagged restricted media). We OR our verdict in,
     * keyed by the row object: its Media-typed field(s) carry the media identity.
     */
    public static boolean forceRowFlag(boolean stock, Object row) {
        try {
            if (stock) return true;
            return rowMatches(row);
        } catch (Throwable t) {
            Logger.printException(() -> "SpoilerShield forceRowFlag failed", t);
            return stock;
        }
    }

    /**
     * Injection point E2: same row verdict as {@link #forceRowFlag}, without the stock
     * passthrough — used to steer the Litho row's cover-path selector.
     */
    public static boolean rowMatches(Object row) {
        try {
            if (row == null) return false;
            if (!Pref.spoilerShield()) return false;

            // The row object exposes the Media via Media-typed fields (439: 01As.A0A
            // carousel media + A0B main media). Probe every field whose type is
            // com.instagram.feed.media.Media by NAME (no stub class available);
            // a verdict on any of them opens the cover path.
            Class<?> c = row.getClass();
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (!f.getType().getName().equals("com.instagram.feed.media.Media")) {
                        continue;
                    }
                    f.setAccessible(true);
                    Object media = f.get(row);
                    if (media == null) continue;
                    Boolean cached = verdictCache.get(media);
                    if (cached == null) {
                        cached = matchReason(media) != null;
                        verdictCache.put(media, cached);
                    }
                    if (cached) return true;
                }
                c = c.getSuperclass();
            }
            return false;
        } catch (Throwable t) {
            Logger.printException(() -> "SpoilerShield rowMatches failed", t);
            return false;
        }
    }

    /**
     * Injection point E2: the Litho row's cover-path selector (true → server Bloks arm,
     * false → local builder arm). We have no Bloks tree, so when our verdict says cover,
     * force the local arm.
     */
    public static boolean steerSelector(boolean stock, Object row) {
        try {
            if (!rowMatches(row)) return stock;
            return false;
        } catch (Throwable t) {
            Logger.printException(() -> "SpoilerShield steerSelector failed", t);
            return stock;
        }
    }

    /**
     * Injection point F: rewrites the fabricated token into the human-readable spoiler
     * reason just before the cover config stores its title. Keyed lookup (not remove):
     * the payload getter can be consulted several times per media.
     */
    public static Object coverTitle(Object titleToken) {
        try {
            if (titleToken instanceof String) {
                String pretty = pendingTitles.get(titleToken);
                if (pretty != null) return pretty;
            }
        } catch (Throwable t) {
            Logger.printException(() -> "SpoilerShield coverTitle failed", t);
        }
        return titleToken;
    }

    // ---------------------------------------------------------------- rules

    /**
     * @return the spoiler reason line, or null when the media does not match /
     *         is older than the configured age limit.
     */
    private static String matchReason(Object media) {
        final long maxAgeSeconds = maxAgeHours() * 3600L;

        final Long takenAt = resolveTakenAt(media);
        if (takenAt == null) return null; // no timestamp -> cannot age-check
        final long age = System.currentTimeMillis() / 1000L - takenAt;
        // hours <= 0 means "no age limit": matching posts stay covered forever.
        if (maxAgeSeconds > 0 && age >= maxAgeSeconds) return null;

        final List<String> parts = new ArrayList<>();

        final Set<String> usernames = splitList(Pref.spoilerShieldUsernames());
        final Set<String> hashtags = splitList(Pref.spoilerShieldHashtags());
        final Set<String> words = splitList(Pref.spoilerShieldWords());

        if (!usernames.isEmpty()) {
            final String username = usernameOf(media);
            if (username != null) {
                for (String u : usernames) {
                    if (username.equalsIgnoreCase(u)) {
                        parts.add("from @" + username);
                        break;
                    }
                }
            }
        }

        final String caption = captionOf(media);
        if (caption != null && !caption.isEmpty()) {
            final String lower = caption.toLowerCase();

            if (!hashtags.isEmpty()) {
                Matcher m = Pattern.compile("#([\\p{L}\\p{N}_]+)").matcher(lower);
                while (m.find()) {
                    if (hashtags.contains(m.group(1))) {
                        parts.add("tagged " + m.group());
                        break;
                    }
                }
            }

            if (!words.isEmpty()) {
                for (String w : words) {
                    if (Pattern.compile("\\b" + Pattern.quote(w) + "\\b")
                            .matcher(lower).find()) {
                        parts.add("contains \"" + w + "\"");
                        break;
                    }
                }
            }
        }

        if (parts.isEmpty()) {
            Logger.printInfo(() -> "SpoilerShield no match: user=" + usernameOf(media)
                    + " captionLen=" + (caption == null ? -1 : caption.length())
                    + " age=" + age + "s");
            return null;
        }

        parts.add("posted " + humanAge(age));
        return join(parts);
    }

    private static long maxAgeHours() {
        try {
            return Long.parseLong(Pref.spoilerShieldMaxAgeHours().trim());
        } catch (Exception e) {
            return 2L; // default
        }
    }

    private static Set<String> splitList(String raw) {
        final Set<String> out = new HashSet<>();
        if (raw == null) return out;
        for (String s : raw.split(",")) {
            s = s.trim().toLowerCase();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String join(List<String> parts) {
        final StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String humanAge(long ageSeconds) {
        if (ageSeconds < 0) return "just now";
        if (ageSeconds < 60) return ageSeconds + "s ago";
        if (ageSeconds < 3600) return (ageSeconds / 60) + "m ago";
        if (ageSeconds < 86400) return (ageSeconds / 3600) + "h ago";
        return (ageSeconds / 86400) + "d ago";
    }

    // ---------------------------------------------------------------- media data

    private static String usernameOf(Object media) {
        try {
            return new MediaData(media).getUserData().getUsername();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String captionOf(Object media) {
        try {
            return new MediaData(media).getDescriptionText();
        } catch (Throwable t) {
            return null;
        }
    }

    /** The media's own square thumbnail URL — the image the binder blurs for the cover. */
    private static String thumbnailUrlOf(Object media) {
        try {
            final MediaData md = new MediaData(media);
            // Photos: image variants (first = smallest). Videos: first video variant's
            // poster/thumbnail URL. Either works — the binder blurs it client-side.
            if (!md.isVideo()) {
                Object variants = md.getImageVariants();
                if (variants instanceof java.util.List && !((java.util.List<?>) variants).isEmpty()) {
                    Object imageData = ((java.util.List<?>) variants).get(0);
                    return (String) imageData.getClass().getMethod("getUrl").invoke(imageData);
                }
            }
            Object videoVariants = md.getVideoVariants();
            if (videoVariants instanceof java.util.List && !((java.util.List<?>) videoVariants).isEmpty()) {
                Object videoData = ((java.util.List<?>) videoVariants).get(0);
                return (String) videoData.getClass().getMethod("getUrl").invoke(videoData);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "SpoilerShield thumbnailUrlOf failed", t);
        }
        return null;
    }

    /**
     * Resolves the media's taken-at epoch (seconds) by probing the Media class's no-arg
     * long getters: method names rotate per version, but there are only a handful and
     * taken-at is always a sane epoch that is <= now (and the smallest such value).
     * The winning Method is cached for the process lifetime.
     */
    private static Long resolveTakenAt(Object media) {
        Method cached = takenAtMethod;
        if (cached != null) {
            try {
                return (Long) cached.invoke(media);
            } catch (Exception e) {
                return null;
            }
        }
        if (takenAtProbed) return null;

        synchronized (SpoilerShield.class) {
            if (takenAtProbed) return null;
            takenAtProbed = true;
            try {
                final long now = System.currentTimeMillis() / 1000L;
                final long saneMin = now - 10L * 365 * 86400; // 10 years back
                Method best = null;
                long bestValue = Long.MAX_VALUE;
                Class<?> c = media.getClass();
                while (c != null && c != Object.class) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getParameterTypes().length != 0
                                || m.getReturnType() != long.class
                                || !Modifier.isPublic(m.getModifiers())) {
                            continue;
                        }
                        m.setAccessible(true);
                        Object v;
                        try {
                            v = m.invoke(media);
                        } catch (Exception e) {
                            continue;
                        }
                        long value = (Long) v;
                        if (value >= saneMin && value <= now && value < bestValue) {
                            best = m;
                            bestValue = value;
                        }
                    }
                    c = c.getSuperclass();
                }
                if (best != null) {
                    takenAtMethod = best;
                    Logger.printInfo(() -> "SpoilerShield resolved takenAt method: "
                            + takenAtMethod.getName());
                    return bestValue;
                }
            } catch (Throwable t) {
                Logger.printException(() -> "SpoilerShield takenAt probe failed", t);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- payload fabrication

    private static final String PAYLOAD_CLASS = "com.instagram.api.schemas.MediaOverlayPayloadSchema";

    /**
     * Builds a MediaOverlayPayloadSchema whose render type drives Instagram's own blurred
     * media-overlay cover, carrying the spoiler reason as title/subtitle text. Constructor
     * shape (439): (ButtonSpec, IconSpec, 0Bdz x4, Boolean, Integer x3, String x6, List) —
     * resolved reflectively; any mismatch logs and returns null (stock behavior).
     */
    private static Object fabricatePayload(String reason, String thumbnailUrl) {
        try {
            final Class<?> clazz = Class.forName(PAYLOAD_CLASS);
            Constructor<?> target = null;
            for (Constructor<?> ctor : clazz.getConstructors()) {
                if (ctor.getParameterTypes().length == 17) {
                    target = ctor;
                    break;
                }
            }
            if (target == null) {
                Logger.printInfo(() -> "SpoilerShield: no 17-arg payload ctor, skipping");
                return null;
            }

            // Unique title token: the builder tail (hook C) matches it to swap in the
            // cover image, since the payload itself carries no image.
            final String token = "piko-spoiler:" + (tokenSeq++);
            pendingTitles.put(token, reason);
            if (thumbnailUrl != null) {
                pendingCoverUrls.put(token, thumbnailUrl);
            }

            // args[N] maps to ctor param p(N+1). Field map (439):
            // p11→A0A = BqK() — REQUIRED subtitle (0iaE throws when null);
            // p12→A0B = Cmb() renderType (null ⇒ builder synthesizes blur URL);
            // p13/p14→A0C/A0D = CEL()/Cuo() secondary text; p16→A0F = getTitle()
            // — carries the token consumed by hook C (rewritten by hook F).
            final Object[] args = new Object[17];
            args[10] = reason;         // BqK() required subtitle
            args[11] = null;           // renderType (Cmb)
            args[12] = reason;         // subtitle slot (DAF)
            args[13] = reason;
            args[14] = reason;
            args[15] = token;          // title (getTitle) — token consumed by hook C
            return target.newInstance(args);
        } catch (Throwable t) {
            Logger.printException(() -> "SpoilerShield payload fabrication failed", t);
            return null;
        }
    }
}
