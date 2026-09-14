/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.instagram.patches.overflowMenuButton.reels.buttons;

import android.view.View;
import android.content.Context;
import app.morphe.extension.instagram.patches.videoQuality.VideoQualityPicker;

public class VideoQualityButton extends ReelButton {
    public VideoQualityButton(Context context, Object mediaObject, int currentMediaIndex) {
        super(context, mediaObject, currentMediaIndex);
    }

    @Override
    public void onClick(View view) {
        VideoQualityPicker.show(this.context, this.mediaObject, this.currentMediaIndex);
    }
}
