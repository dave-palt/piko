/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.localShareLink;

import java.util.concurrent.Callable;

import app.morphe.extension.instagram.constants.PostType;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.settings.SettingsStatus;
import app.morphe.extension.instagram.utils.IgStr;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Serves share URLs locally instead of the third_party_sharing network
 * round-trip. The server response only wraps a link that is fully derivable
 * from on-device data (shortcode / username), plus an igsh tracking token that
 * the sanitize patch strips anyway — so the request is pure latency.
 *
 * Injected at the top of the request-builder methods:
 *   X/MFy.A00 (media permalink), X/MFy.A03 (story item), X/KFb.A00 (profile).
 * When enabled, returns a locally-completed X/2Hd task; on any failure
 * returns null so the caller falls back to the original network request.
 */
public final class LocalShareLink {

    private LocalShareLink() {
    }

    /**
     * Media permalink (X/MFy.A00). {@code mediaObject} is the raw
     * com.instagram.feed.media.Media; shortcode/post-type are resolved through
     * MediaData — the same reflection path the fork's copy-link feature uses.
     * {@code imgIndex} is the carousel slide index (0 = none/whole post).
     */
    public static Object mediaPermalink(Object mediaObject, int imgIndex) {
        if (!enabled() || mediaObject == null) {
            return null;
        }
        try {
            MediaData mediaData = new MediaData(mediaObject);
            PostType postType = mediaData.getPostType();
            String code = mediaData.getShortcode();

            String link;
            if (postType == PostType.STORY) {
                String username = mediaData.getUserData().getUsername();
                link = "https://www.instagram.com/stories/" + username + "/" + mediaData.getPostID() + "/";
            } else if (postType == PostType.REEL) {
                link = "https://www.instagram.com/reel/" + code + "/";
            } else {
                link = "https://www.instagram.com/p/" + code + "/";
                if (postType == PostType.CAROUSEL && imgIndex > 0) {
                    link += "?img_index=" + imgIndex;
                }
            }
            Object task = completedTask(link, "E11", "Dt3", "Pp9");
            if (task != null) {
                Utils.showToastShort(IgStr.str("piko_copied"));
            }
            return task;
        } catch (Throwable t) {
            Logger.printException(() -> "LocalShareLink mediaPermalink failed", t);
            return null;
        }
    }

    /**
     * Story item URL (X/MFy.A03). The repo method trims {@code mediaId} at the
     * first '_' before requesting; the local URL mirrors that.
     */
    public static Object storyItemUrl(String username, String mediaId) {
        if (!enabled() || username == null || mediaId == null) {
            return null;
        }
        try {
            int underscore = mediaId.indexOf('_');
            if (underscore > 0) {
                mediaId = mediaId.substring(0, underscore);
            }
            String url = "https://www.instagram.com/stories/" + username + "/" + mediaId + "/";
            return completedTask(url, "E1A", "com.instagram.request.StoryItemUrlResponseImpl",
                    "com.instagram.request.StoryItemUrlResponse");
        } catch (Throwable t) {
            Logger.printException(() -> "LocalShareLink storyItemUrl failed", t);
            return null;
        }
    }

    /** Profile URL (X/KFb.A00). A blank username is that repo's own "skip" sentinel. */
    public static Object profileUrl(String username) {
        if (!enabled() || username == null || username.trim().isEmpty()) {
            return null;
        }
        try {
            String url = "https://www.instagram.com/" + username + "/";
            return completedTask(url, "E0j", "Dqc", "Pp0");
        } catch (Throwable t) {
            Logger.printException(() -> "LocalShareLink profileUrl failed", t);
            return null;
        }
    }

    private static boolean enabled() {
        return Pref.sanitizeShareLinks() && SettingsStatus.sanitizeShareLinks;
    }

    /**
     * Builds a no-network X/2Hd task resolving to the same response-object
     * shape the server round-trip would have produced:
     *   4ro(holder) in 3yw(callable) in 3pN via 2Hd.A01 — the same wrapper
     * Instagram itself builds when a request short-circuits, so the loader
     * (3pN.run) delivers the holder to listeners/awaiters unchanged.
     * All classes are resolved reflectively (obfuscated names differ per
     * version); any failure returns null = fall back to the network request.
     */
    private static Object completedTask(String url, String holderName, String implName, String intfName) {
        try {
            Object response = buildResponse(holderName, implName, intfName, url);
            if (response == null) {
                return null;
            }

            Class<?> taskFutureClass = Class.forName("X.3yn");
            Class<?> successClass = Class.forName("X.4ro");
            Class<?> callableFutureClass = Class.forName("X.3yw");
            Class<?> taskClass = Class.forName("X.2Hd");

            final Object success = successClass.getDeclaredConstructor(Object.class).newInstance(response);

            // X/3yw wraps a Callable; its run() (called by 3pN.run) completes the future.
            Callable<Object> callable = () -> success;
            Object future = callableFutureClass
                    .getDeclaredConstructor(Callable.class, int.class, boolean.class, boolean.class)
                    .newInstance(callable, 0, false, false);

            // X/2Hd.A01(LX/3yn;Ljava/lang/String;Ljava/lang/String;)LX/3pN;
            return taskClass
                    .getDeclaredMethod("A01", taskFutureClass, String.class, String.class)
                    .invoke(null, future, "piko_local_share", "local://share-url");
        } catch (Throwable t) {
            Logger.printException(() -> "LocalShareLink task build failed", t);
            return null;
        }
    }

    /**
     * holder{A00: impl(url)} where impl implements intfName with a single
     * public A00:String URL field. Holders have public no-arg ctors; impls are
     * Redex ctor-stripped (IG constructs them via the super ctor inline), so
     * they are allocated via Unsafe and the A00 field set directly.
     */
    private static Object buildResponse(String holderName, String implName, String intfName, String url)
            throws Exception {
        Class<?> holderClass = Class.forName("X." + holderName);
        Class<?> implClass = Class.forName(
                implName.startsWith("com.") ? implName : "X." + implName);
        Class<?> intfClass = Class.forName(
                intfName.startsWith("com.") ? intfName : "X." + intfName);
        if (!intfClass.isAssignableFrom(implClass)) {
            return null;
        }

        Object impl = unsafeInstance(implClass);
        implClass.getField("A00").set(impl, url);

        Object holder = holderClass.getDeclaredConstructor().newInstance();
        holderClass.getField("A00").set(holder, impl);
        return holder;
    }

    private static Object unsafeInstance(Class<?> clazz) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, clazz);
    }
}
