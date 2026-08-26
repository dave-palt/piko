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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.patches.copyMediaLink.CopyMediaLinkUtils;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.Dim;

public class ShareSheetCopyLink {

    // direct_private_share_bottom_control_container (Instagram 435.0.0.37.76):
    // hosts the external share options strip (copy link / share icons).
    private static final int BOTTOM_CONTROLS_ID = 0x7f0b12b2;

    // direct_private_share_recipients_recycler_view era id; only used as a
    // last-resort anchor when the bottom container cannot be located.
    private static final int RECIPIENTS_RV_ID = 0x7f0b129b;

    private static final String ROW_TAG = "piko_share_sheet_copy_link";

    private static final String MEDIA_CLASS = "com.instagram.feed.media.Media";
    private static final String SESSION_CLASS = "com.instagram.common.session.UserSession";

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
     * Finds every field of {@code type} declared on the instance's class
     * hierarchy. Obfuscated field names rotate between releases, but the
     * concrete types (Media / UserSession) are stable.
     */
    private static List<Field> fieldsOfType(Object instance, String typeName) {
        List<Field> out = new ArrayList<>();
        try {
            Class<?> clazz = instance.getClass();
            while (clazz != null) {
                for (Field field : clazz.getDeclaredFields()) {
                    if (field.getType().getName().equals(typeName)) {
                        field.setAccessible(true);
                        out.add(field);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            Logger.printException(() -> "ShareSheetCopyLink fieldsOfType " + typeName, e);
        }
        return out;
    }

    private static Object firstNonNull(Object instance, List<Field> fields) {
        for (Field field : fields) {
            try {
                Object value = field.get(instance);
                if (value != null) {
                    return value;
                }
            } catch (Exception e) {
                Logger.printException(() -> "ShareSheetCopyLink firstNonNull", e);
            }
        }
        return null;
    }

    /**
     * DirectShareSheetFragment inherits a readable {@code getSession()} from
     * its base fragment class (verified on 435: LX/AXi;->getSession()).
     */
    private static Object callGetSession(Object fragment) {
        try {
            Class<?> clazz = fragment.getClass();
            while (clazz != null) {
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getName().equals("getSession")
                            && method.getParameterCount() == 0) {
                        method.setAccessible(true);
                        return method.invoke(fragment);
                    }
                }
                clazz = clazz.getSuperclass();
            }
        } catch (Exception e) {
            Logger.printException(() -> "ShareSheetCopyLink callGetSession", e);
        }
        return null;
    }

    /**
     * Called right after DirectShareSheetFragment.onViewCreated() invokes its
     * super. Fields are assigned later in onViewCreated, so the actual row
     * insertion is posted to run after the method completes.
     */
    public static void addCopyLinkRow(Object fragment, View view) {
        try {
            if (!(view instanceof ViewGroup)) {
                return;
            }
            view.post(() -> {
                try {
                    insertRow(fragment, (ViewGroup) view);
                } catch (Exception e) {
                    Logger.printException(() -> "ShareSheetCopyLink addCopyLinkRow", e);
                }
            });
        } catch (Exception e) {
            Logger.printException(() -> "ShareSheetCopyLink addCopyLinkRow", e);
        }
    }

    private static void insertRow(Object fragment, ViewGroup root) {
        if (findTaggedView(root) != null) {
            return; // already injected
        }

        View anchor = findBottomControls(fragment, root);
        if (anchor == null) {
            View recyclerView = findViewByIdDeep(root, RECIPIENTS_RV_ID);
            if (recyclerView == null || !(recyclerView.getParent() instanceof ViewGroup)) {
                Logger.printInfo(() -> "ShareSheetCopyLink: no anchor view found");
                return;
            }
            anchor = recyclerView;
        }

        Context context = anchor.getContext();

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Dim.dp16, Dim.dp16 / 2, Dim.dp16, Dim.dp16 / 2);
        row.setTag(ROW_TAG);

        ImageView icon = new ImageView(context);
        UI.setThemedIcon(icon, UI.DRAWABLE_LINK_ICON);
        row.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(context);
        label.setText(str("piko_copy_media_link"));
        label.setTextSize(16);
        label.setTextColor(UI.getThemedColour("igds_color_primary_text"));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMargins(Dim.dp12, 0, 0, 0);
        row.addView(label, labelParams);

        row.setOnClickListener(v -> copyMediaLink(fragment, v));

        // Insert directly below the bottom controls block (the copy-link /
        // share option icons strip) so the row sits alongside the icons.
        // The fork2 build proved this sheet stacks children vertically.
        ViewGroup parent;
        int index;
        if (anchor.getParent() instanceof LinearLayout) {
            parent = (ViewGroup) anchor.getParent();
            index = parent.indexOfChild(anchor) + 1;
        } else if (anchor instanceof LinearLayout) {
            parent = (ViewGroup) anchor;
            index = parent.getChildCount();
        } else {
            parent = (ViewGroup) anchor;
            index = -1;
        }
        Logger.printInfo(() -> "ShareSheetCopyLink: anchor=" + anchor.getClass().getName()
                + " parent=" + (parent != null ? parent.getClass().getName() : "null")
                + " index=" + index);
        parent.addView(row, index, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static void copyMediaLink(Object fragment, View v) {
        try {
            Object media = firstNonNull(fragment, fieldsOfType(fragment, MEDIA_CLASS));
            if (media == null) {
                app.morphe.extension.shared.Utils.showToastShort(str("piko_fail_no_file"));
                return;
            }

            Object session = callGetSession(fragment);
            if (session == null) {
                session = firstNonNull(fragment, fieldsOfType(fragment, SESSION_CLASS));
            }
            if (session == null) {
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
    }

    /**
     * Preferred anchor: the readable {@code bottomControlsContainer} field
     * (Instagram 435). Fallback: the view with its resource id.
     */
    private static View findBottomControls(Object fragment, ViewGroup root) {
        Object container = readField(fragment, "bottomControlsContainer");
        if (container instanceof ViewGroup) {
            return (ViewGroup) container;
        }
        View byId = findViewByIdDeep(root, BOTTOM_CONTROLS_ID);
        if (byId instanceof ViewGroup) {
            return byId;
        }
        return null;
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

    private static View findTaggedView(View view) {
        Object tag = view.getTag();
        if (tag instanceof String && ROW_TAG.equals(tag)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTaggedView(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
