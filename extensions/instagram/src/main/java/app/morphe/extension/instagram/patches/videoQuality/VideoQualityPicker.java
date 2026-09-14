/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.videoQuality;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.instagram.entity.InstagramDialogBox;
import app.morphe.extension.instagram.entity.MediaData;

/**
 * Per-post/reel quality picker, opened from the ⋮ overflow menu. Lists the actual
 * playback variants of the CURRENT media (same resolution tags the download
 * variant dialog shows) plus "Default" to clear any override. Picking a variant
 * registers a per-reel URL override in VideoQuality and re-opens the media so the
 * player re-binds with the chosen URL.
 */
public final class VideoQualityPicker {

    private VideoQualityPicker() {}

    private interface IntSupplier { int get() throws Exception; }

    private static int safeInt(IntSupplier s) {
        try { return s.get(); } catch (Exception e) { return 0; }
    }

    /** "720p · codec 102" (or "720x1280 · codec 102" for odd sizes), unique via #n. */
    private static String variantLabel(
            app.morphe.extension.instagram.entity.VideoData v, java.util.List<String> existing) {
        int h = safeInt(v::getHeight);
        int w = safeInt(v::getWidth);
        String codec;
        try { codec = v.getVariantTag().replaceAll("^.*-", ""); } catch (Exception e) { codec = "?"; }
        String size = (h == 720 || h == 1080 || h == 480 || h == 360 || h == 2160) ? h + "p" : h + "x" + w;
        String base = size + " · codec " + codec;
        if (!existing.contains(base)) return base;
        int n = 2;
        while (existing.contains(base + "  #" + n)) n++;
        return base + "  #" + n;
    }

    public static void show(Context context, Object mediaObject, int currentMediaIndex) {
        try {
            MediaData mediaData = new MediaData(mediaObject, null);
            MediaData current = mediaData.getMediaAt(currentMediaIndex);

            if (!current.isVideo()) {
                Utils.showToastShort(str("piko_video_quality_not_video"));
                return;
            }

            List<app.morphe.extension.instagram.entity.VideoData> variants = current.getVideoVariants();
            if (variants == null || variants.isEmpty()) {
                Utils.showToastShort(str("piko_video_quality_no_variants"));
                return;
            }

            InstagramDialogBox dialog = new InstagramDialogBox(context);

            // Human labels: "720p · codec 102", sorted best-first (largest height,
            // then width). Same-res variants differ in codec/bitrate — bytes and
            // sometimes decode path — so the codec stays visible; exact duplicates
            // get a #n suffix so every row is distinguishable.
            java.util.List<app.morphe.extension.instagram.entity.VideoData> sorted =
                    new java.util.ArrayList<>(variants);
            java.util.Collections.sort(sorted, (a, b) -> {
                int ha = safeInt(() -> a.getHeight()), hb = safeInt(() -> b.getHeight());
                if (hb != ha) return Integer.compare(hb, ha);
                int wa = safeInt(() -> a.getWidth()), wb = safeInt(() -> b.getWidth());
                return Integer.compare(wb, wa);
            });

            java.util.ArrayList<String> options = new java.util.ArrayList<>();
            options.add(str("piko_array_video_quality_default"));
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (app.morphe.extension.instagram.entity.VideoData v : sorted) {
                String label = variantLabel(v, labels);
                labels.add(label);
                options.add(label);
            }
            CharSequence[] items = options.toArray(new CharSequence[0]);

            final List<app.morphe.extension.instagram.entity.VideoData> finalVariants = sorted;

            dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    try {
                        if (which == 0) {
                            // "Default" — clear is implicit: not choosing any override
                            // leaves the global mode in charge. Show what applies now.
                            Utils.showToastShort(str("piko_video_quality_using_global"));
                            return;
                        }
                        app.morphe.extension.instagram.entity.VideoData chosen = finalVariants.get(which - 1);
                        VideoQuality.overrideFor(chosen.getUrl());
                        Utils.showToastShort(str("piko_video_quality_applied"));
                        // The player re-binds on next bind cycle; scrolling away and
                        // back (or pausing/resuming) applies it. No restart needed.
                    } catch (Exception e) {
                        Logger.printException(() -> "VideoQualityPicker onClick failed", e);
                        Utils.showToastShort(e.getMessage());
                    }
                }
            });

            dialog.setTitle(str("piko_video_quality"));
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);

            Dialog dlg = dialog.getDialog();
            dlg.show();
        } catch (Exception e) {
            Logger.printException(() -> "VideoQualityPicker.show failed", e);
            Utils.showToastShort(e.getMessage());
        }
    }
}
