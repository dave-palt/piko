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
import app.morphe.extension.instagram.entity.VideoData;
import app.morphe.extension.instagram.patches.videoQuality.CodecCapabilities.CodecInfo;

/**
 * Per-post/reel quality picker, opened from the ⋮ overflow menu. Lists the actual
 * playback variants of the CURRENT media with device-aware badges:
 *   720p · HEVC (hw)   ← hardware decode, cheap
 *   720p · AV1 (sw!)   ← software decode, CPU burner
 * plus a "Default" row and a ★ recommended row = smallest battery cost
 * (hardware-decodable, then lowest resolution, then most efficient codec).
 */
public final class VideoQualityPicker {

    private VideoQualityPicker() {}

    private interface IntSupplier { int get() throws Exception; }

    private static int safeInt(IntSupplier s) {
        try { return s.get(); } catch (Exception e) { return 0; }
    }

    private static final class Row {
        final VideoData variant;
        final String label;
        final int height;
        final CodecInfo codec;
        final boolean recommended;

        Row(VideoData variant, String label, int height, CodecInfo codec, boolean recommended) {
            this.variant = variant;
            this.label = label;
            this.height = height;
            this.codec = codec;
            this.recommended = recommended;
        }
    }

    /** "720p · HEVC (hw)" / "480p · AV1 (sw!)" — unique via #n. */
    private static String rowLabel(VideoData v, CodecInfo codec, List<String> existing) {
        int h = safeInt(v::getHeight);
        int w = safeInt(v::getWidth);
        String size = (h == 720 || h == 1080 || h == 480 || h == 360 || h == 2160) ? h + "p" : h + "x" + w;
        String badge;
        if (codec.decodable && codec.hardware) badge = "(hw)";
        else if (codec.decodable) badge = "(sw!)";
        else badge = "(unsupported)";
        String base = size + " · " + codec.name + " " + badge;
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

            List<VideoData> variants = current.getVideoVariants();
            if (variants == null || variants.isEmpty()) {
                Utils.showToastShort(str("piko_video_quality_no_variants"));
                return;
            }

            // Build rows, best-first by resolution (height then width).
            // IG sometimes lists the SAME stream several times under different type
            // ids (identical URL) — collapse those into one row so the dialog never
            // shows duplicates (which also made every row read "playing").
            java.util.List<Row> rows = new java.util.ArrayList<>();
            java.util.Set<String> seenUrls = new java.util.HashSet<>();
            for (VideoData v : variants) {
                final String url;
                try { url = v.getUrl(); } catch (Exception e) { url = null; }
                Logger.printInfo(() -> "videoQuality variant: h=" + safeInt(v::getHeight)
                        + " type=" + typeOf(v) + " url=" + (url == null ? "null" : url));
                if (url != null && !seenUrls.add(url)) continue; // duplicate stream
                rows.add(new Row(v, null, safeInt(v::getHeight), null, false));
            }
            java.util.Collections.sort(rows, (a, b) -> {
                if (b.height != a.height) return Integer.compare(b.height, a.height);
                return Integer.compare(safeInt(() -> b.variant.getWidth()), safeInt(() -> a.variant.getWidth()));
            });

            // Codec info per row.
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                String url = null;
                try { url = r.variant.getUrl(); } catch (Exception ignored) {}
                int type = typeOf(r.variant);
                CodecInfo info = CodecCapabilities.of(type, url);
                Row withCodec = new Row(r.variant, null, r.height, info, false);
                rows.set(i, withCodec);
            }

            // Recommended = hardware-decodable, lowest resolution, most efficient codec.
            int best = -1;
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (!r.codec.decodable || !r.codec.hardware) continue;
                if (best == -1) { best = i; continue; }
                Row b = rows.get(best);
                if (r.height < b.height
                        || (r.height == b.height && r.codec.efficiency < b.codec.efficiency)) {
                    best = i;
                }
            }

            // Labels: ★ recommended, ● = currently playing for this media.
            java.util.List<String> seen = new java.util.ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                String label = rowLabel(r.variant, r.codec, seen);
                seen.add(label);
                boolean rec = (i == best);
                String url = null;
                try { url = r.variant.getUrl(); } catch (Exception ignored) {}
                boolean now = VideoQuality.isCurrentlyUsed(url);
                StringBuilder sb = new StringBuilder();
                if (rec) sb.append("★ ");
                sb.append(label);
                if (now) sb.append("  ● playing");
                rows.set(i, new Row(r.variant, sb.toString(), r.height, r.codec, rec));
            }

            InstagramDialogBox dialog = new InstagramDialogBox(context);
            java.util.ArrayList<String> options = new java.util.ArrayList<>();
            String defaultRow = str("piko_array_video_quality_default");
            if (VideoQuality.hasOverride()) defaultRow += "  ✓";
            options.add(defaultRow);
            for (Row r : rows) options.add(r.label);
            CharSequence[] items = options.toArray(new CharSequence[0]);

            final List<Row> finalRows = rows;

            dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    try {
                        if (which == 0) {
                            VideoQuality.clearOverride();
                            Utils.showToastShort(str("piko_video_quality_cleared"));
                            return;
                        }
                        Row chosen = finalRows.get(which - 1);
                        VideoQuality.overrideCombo(chosen.height, chosen.codec);
                        Utils.showToastShort(str("piko_video_quality_applied_next"));
                        // Sticky for the session: this and following reels get the
                        // closest variant to this combo on their next bind.
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

    /** codec type int from the variant tag ("1280x720-102" -> 102). */
    private static int typeOf(VideoData v) {
        try {
            String tag = v.getVariantTag();
            int dash = tag.lastIndexOf('-');
            return dash >= 0 ? Integer.parseInt(tag.substring(dash + 1).trim()) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
