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
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
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

    // When media cannot be resolved at row-insert time (lazy fields are still
    // null), retry a few times so galleries still get the dual-button layout.
    private static final int MAX_RESOLVE_RETRIES = 3;
    private static final long RESOLVE_RETRY_DELAY_MS = 800L;

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

    private static Object resolveSession(Object fragment) {
        Object session = callGetSession(fragment);
        if (session == null) {
            session = firstNonNull(fragment, fieldsOfType(fragment, SESSION_CLASS));
        }
        return session;
    }

    /**
     * Resolves the share-sheet media at call time.
     *
     * On 435 neither Media field is guaranteed to be assigned when the sheet
     * becomes interactive: A0Z is owned by a lazy delegate (X/PHA reads the
     * fragment arguments) and A0a comes from a nullable accessor. So after the
     * direct field scan we also probe the fragment's own model fields for a
     * parameterless method returning Media — Instagram's own pattern (the Wi6
     * holder exposes CNP()) — which survives obfuscation renames.
     */
    private static Object resolveMedia(Object fragment) {
        List<Field> mediaFields = fieldsOfType(fragment, MEDIA_CLASS);
        Object media = firstNonNull(fragment, mediaFields);
        if (media != null) {
            Logger.printInfo(() -> "ShareSheetCopyLink: media from field scan ("
                    + mediaFields.size() + " Media fields)");
            return media;
        }

        // Fallback: any non-null field exposing a no-arg method whose return
        // type is exactly Media.
        try {
            List<Field> all = new ArrayList<>();
            Class<?> clazz = fragment.getClass();
            while (clazz != null) {
                for (Field field : clazz.getDeclaredFields()) {
                    field.setAccessible(true);
                    all.add(field);
                }
                clazz = clazz.getSuperclass();
            }
            for (Field field : all) {
                Object value;
                try {
                    value = field.get(fragment);
                } catch (Exception ignored) {
                    continue;
                }
                if (value == null || value instanceof View || value instanceof Context) {
                    continue;
                }
                Class<?> valueClass = value.getClass();
                if (valueClass.getName().startsWith("java.") || valueClass.isPrimitive()) {
                    continue;
                }
                for (Method method : valueClass.getDeclaredMethods()) {
                    if (method.getParameterCount() != 0
                            || !method.getReturnType().getName().equals(MEDIA_CLASS)) {
                        continue;
                    }
                    try {
                        method.setAccessible(true);
                        Object result = method.invoke(value);
                        Logger.printInfo(() -> "ShareSheetCopyLink: accessor "
                                + valueClass.getName() + "." + method.getName()
                                + "() -> " + (result != null ? "media" : "null"));
                        if (result != null) {
                            return result;
                        }
                    } catch (Throwable t) {
                        Logger.printInfo(() -> "ShareSheetCopyLink: accessor "
                                + valueClass.getName() + "." + method.getName()
                                + "() threw " + t);
                    }
                }
            }
        } catch (Exception e) {
            Logger.printException(() -> "ShareSheetCopyLink resolveMedia fallback", e);
        }
        Logger.printInfo(() -> "ShareSheetCopyLink: media resolution failed"
                + " (Media fields=" + mediaFields.size() + ")");
        return null;
    }

    private static Object buildMediaData(Object fragment) {
        Object media = resolveMedia(fragment);
        if (media == null) {
            return null;
        }
        Object session = resolveSession(fragment);
        if (session == null) {
            Logger.printInfo(() -> "ShareSheetCopyLink: session unresolved");
            return null;
        }
        try {
            return new MediaData(media, (com.instagram.common.session.UserSession) session);
        } catch (Exception e) {
            Logger.printException(() -> "ShareSheetCopyLink MediaData ctor", e);
            return null;
        }
    }

    private static int safeCarouselSize(Object mediaData) {
        try {
            return ((MediaData) mediaData).getCarouselSize();
        } catch (Exception e) {
            Logger.printException(() -> "ShareSheetCopyLink getCarouselSize", e);
            return -1;
        }
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
                + " parent=" + parent.getClass().getName() + " index=" + index);

        buildRowInto(fragment, root, parent, index, 0);
    }

    /**
     * Builds the row for the resolved media state: two side-by-side buttons
     * (copy all / copy current) for carousels, a single copy-link button
     * otherwise. When media is not yet resolvable the single-button variant is
     * inserted and retried, so a gallery can still upgrade to the dual layout.
     */
    private static void buildRowInto(Object fragment, ViewGroup root, ViewGroup parent,
                                     int index, int attempt) {
        Object mediaData = buildMediaData(fragment);
        int carouselSize = mediaData != null ? safeCarouselSize(mediaData) : -1;
        boolean dual = carouselSize > 1;

        Logger.printInfo(() -> "ShareSheetCopyLink: row variant=" + (dual ? "dual" : "single")
                + " carousel=" + carouselSize + " attempt=" + attempt);

        LinearLayout row = dual
                ? dualButtonRow(fragment, parent)
                : singleButtonRow(fragment, parent);

        row.setTag(ROW_TAG);
        parent.addView(row, index, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!dual && attempt < MAX_RESOLVE_RETRIES) {
            final int nextAttempt = attempt + 1;
            row.postDelayed(() -> {
                try {
                    upgradeIfGallery(fragment, root, nextAttempt);
                } catch (Exception e) {
                    Logger.printException(() -> "ShareSheetCopyLink retry", e);
                }
            }, RESOLVE_RETRY_DELAY_MS);
        }
    }

    /** Replaces a single-button row with the dual layout once media resolves. */
    private static void upgradeIfGallery(Object fragment, ViewGroup root, int attempt) {
        View existing = findTaggedView(root);
        if (existing == null || !(existing.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) existing.getParent();
        int index = parent.indexOfChild(existing);

        Object mediaData = buildMediaData(fragment);
        if (mediaData == null || safeCarouselSize(mediaData) <= 1) {
            if (attempt < MAX_RESOLVE_RETRIES) {
                final int next = attempt + 1;
                existing.postDelayed(() -> {
                    try {
                        upgradeIfGallery(fragment, root, next);
                    } catch (Exception e) {
                        Logger.printException(() -> "ShareSheetCopyLink retry", e);
                    }
                }, RESOLVE_RETRY_DELAY_MS);
            }
            return; // still unresolved (or genuinely single media): keep single row
        }

        Logger.printInfo(() -> "ShareSheetCopyLink: upgrading row to dual at attempt " + attempt);
        parent.removeView(existing);
        buildRowInto(fragment, root, parent, index, attempt);
    }

    private static LinearLayout dualButtonRow(Object fragment, ViewGroup anchorParent) {
        Context context = anchorParent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout all = makeButton(context, str("piko_copy_all_media_links"), true);
        all.setOnClickListener(v -> copyNow(fragment, v, true));
        row.addView(all, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout current = makeButton(context, str("piko_copy_current_media_link"), true);
        current.setOnClickListener(v -> copyNow(fragment, v, false));
        row.addView(current, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    private static LinearLayout singleButtonRow(Object fragment, ViewGroup anchorParent) {
        Context context = anchorParent.getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Dim.dp16, Dim.dp16 / 2, Dim.dp16, Dim.dp16 / 2);

        LinearLayout button = makeButton(context, str("piko_copy_media_link"), false);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(v -> copyNow(fragment, v, false));
        row.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** One copy-action button: themed link icon + label, centered content. */
    private static LinearLayout makeButton(Context context, String label, boolean center) {
        LinearLayout button = new LinearLayout(context);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(center ? Gravity.CENTER : Gravity.CENTER_VERTICAL);
        int vertPad = Dim.dp16 / 2;
        button.setPadding(Dim.dp12, vertPad, Dim.dp12, vertPad);

        ImageView icon = new ImageView(context);
        UI.setThemedIcon(icon, UI.DRAWABLE_LINK_ICON);
        button.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(context);
        text.setText(label);
        text.setTextSize(center ? 14 : 16);
        text.setTextColor(UI.getThemedColour("igds_color_primary_text"));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.setMargins(Dim.dp12 / 2, 0, 0, 0);
        if (center) {
            textParams.gravity = Gravity.CENTER_VERTICAL;
        }
        button.addView(text, textParams);
        return button;
    }

    /** Copies straight to the clipboard; no intermediate dialog. */
    private static void copyNow(Object fragment, View v, boolean all) {
        try {
            Object mediaData = buildMediaData(fragment);
            if (mediaData == null) {
                Logger.printInfo(() -> "ShareSheetCopyLink: copyNow failed - no media/session");
                Utils.showToastShort(str("piko_fail_no_file"));
                return;
            }
            MediaData data = (MediaData) mediaData;
            String result;
            if (all) {
                StringBuilder builder = new StringBuilder();
                int size = data.getCarouselSize();
                for (int i = 0; i < size; i++) {
                    if (i > 0) {
                        builder.append('\n');
                    }
                    builder.append(data.getMediaAt(i).getMediaLink());
                }
                result = builder.toString();
            } else {
                result = data.getMediaAt(0).getMediaLink();
            }

            if (result == null || result.isEmpty()) {
                Logger.printInfo(() -> "ShareSheetCopyLink: copyNow - empty link (all=" + all + ")");
                Utils.showToastShort(str("piko_fail_no_file"));
                return;
            }
            Utils.setClipboard(result);
            Utils.showToastShort(str("piko_copied_media_link"));
            Logger.printInfo(() -> "ShareSheetCopyLink: copied " + result.length()
                    + " chars (all=" + all + ")");
        } catch (Exception e) {
            Logger.printException(() -> "ShareSheetCopyLink copyNow", e);
            Utils.showToastShort(str("piko_fail_no_file"));
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
