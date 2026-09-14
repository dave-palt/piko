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
            java.util.ArrayList<String> options = new java.util.ArrayList<>();
            options.add(str("piko_array_video_quality_default"));
            for (app.morphe.extension.instagram.entity.VideoData v : variants) {
                options.add(v.getVariantTag());
            }
            CharSequence[] items = options.toArray(new CharSequence[0]);

            final List<app.morphe.extension.instagram.entity.VideoData> finalVariants = variants;

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
