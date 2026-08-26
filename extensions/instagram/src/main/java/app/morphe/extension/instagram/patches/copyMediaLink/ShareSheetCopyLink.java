/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.copyMediaLink;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.patches.copyMediaLink.CopyMediaLinkUtils;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

public class ShareSheetCopyLink {

    // direct_private_share_recipients_recycler_view (Instagram 444.0.0.46.85)
    private static final int RECIPIENTS_RV_ID = 0x7f0b129b;

    // Obfuscated DirectShareSheetFragment fields (444.0.0.46.85). Read lazily at
    // click time because they are assigned after onViewCreated begins.
    private static final String MEDIA_FIELD = "A0Y";
    private static final String USER_SESSION_FIELD = "HAQ";

    private static final String ROW_TAG = "piko_share_sheet_copy_link";

    private static Object readField(Object instance, String name) {
        try {
            Class<?> clazz = instance.getClass();
            while (clazz != null) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (field.getName().equals(name)) {
                        field.setAccessible(true);
                        return field.get(instance);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            Logger.printException(() -> "ShareSheetCopyLink readField " + name, e);
        }
        return null;
    }

    /**
     * Called right after DirectShareSheetFragment.onViewCreated() invokes its super.
     * Inserts a themed "Copy link" row above the recipients list.
     */
    public static void addCopyLinkRow(Object fragment, View view) {
        try {
            if (!(view instanceof ViewGroup)) {
                return;
            }

            View recyclerView = findViewByIdDeep((ViewGroup) view, RECIPIENTS_RV_ID);
            if (!(recyclerView.getParent() instanceof ViewGroup)) {
                Logger.printInfo(() -> "ShareSheetCopyLink: recipients RecyclerView not found");
                return;
            }

            ViewGroup parent = (ViewGroup) recyclerView.getParent();
            if (findTaggedRow(parent) != null) {
                return; // already injected
            }

            Context context = recyclerView.getContext();

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Dim.dp16, Dim.dp16 / 2, Dim.dp16, Dim.dp16 / 2);
            row.setTag(ROW_TAG);

            ImageView icon = new ImageView(context);
            UI.setThemedIcon(icon, UI.DRAWABLE_LINK_ICON);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(icon, iconParams);

            TextView label = new TextView(context);
            label.setText(str("piko_copy_media_link"));
            label.setTextSize(16);
            label.setTextColor(UI.getThemedColour("igds_color_primary_text"));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelParams.setMargins(Dim.dp12, 0, 0, 0);
            row.addView(label, labelParams);

            row.setOnClickListener(v -> {
                try {
                    Object media = readField(fragment, MEDIA_FIELD);
                    Object session = readField(fragment, USER_SESSION_FIELD);
                    if (media == null) {
                        app.morphe.extension.shared.Utils.showToastShort(str("piko_fail_no_file"));
                        return;
                    }
                    CopyMediaLinkUtils.copyMediaLinkDialog(
                            v.getContext(),
                            (com.instagram.common.session.UserSession) session,
                            new MediaData(media, (com.instagram.common.session.UserSession) session),
                            0);
                } catch (Exception e) {
                    Logger.printException(() -> "ShareSheetCopyLink onClick", e);
                }
            });

            int index = parent.indexOfChild(recyclerView);
            parent.addView(row, Math.max(index, 0), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception e) {
            Logger.printException(() -> "ShareSheetCopyLink addCopyLinkRow", e);
        }
    }

    private static View findViewByIdDeep(View view, int id) {
        if (view.getId() == id) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewByIdDeep(group.getChildAt(i), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static View findTaggedRow(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (ROW_TAG.equals(child.getTag())) {
                return child;
            }
        }
        return null;
    }
}
