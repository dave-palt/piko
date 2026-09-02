/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.sessionbackup;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.instagram.constants.UI;

import static app.morphe.extension.instagram.utils.IgStr.str;

/**
 * Injects an "Import login session" pill onto the login screen
 * (com.instagram.modal.ModalActivity) when no account is logged in,
 * so a fresh install can restore a session without reaching piko
 * settings (which requires being logged in).
 *
 * <p>Injection point: end of ModalActivity.onCreate, after super and
 * after the content view exists. The pill is added to the activity's
 * android.R.id.content root with bottom-center gravity.</p>
 */
public final class LoginScreenImportButton {

    private LoginScreenImportButton() {}

    /**
     * Injection point. Called from ModalActivity.onCreate (patched).
     *
     * @param activity the ModalActivity instance (this)
     */
    public static void addImportButton(Activity activity) {
        try {
            Logger.printInfo(() -> "LoginScreenImportButton: onCreate hook fired");
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                Logger.printInfo(() -> "LoginScreenImportButton: activity not usable, skipping");
                return;
            }

            // Only show when logged out: no stored auth headers.
            boolean loggedOut = SessionBackup.isLoggedOut(activity);
            Logger.printInfo(() -> "LoginScreenImportButton: loggedOut=" + loggedOut);
            if (!loggedOut) {
                return;
            }

            ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
            if (content == null) {
                Logger.printInfo(() -> "LoginScreenImportButton: no content view yet");
                return;
            }

            final ViewGroup root = content;
            // Defer one frame so the login UI is laid out and the pill lands on top.
            root.post(() -> addPill(activity, root));
        } catch (Exception e) {
            Logger.printException(() -> "LoginScreenImportButton.addImportButton failed", e);
        }
    }

    private static void addPill(Activity activity, ViewGroup content) {
        try {
            // Idempotency: never add twice on the same activity.
            for (int i = 0, n = content.getChildCount(); i < n; i++) {
                if (content.getChildAt(i).getTag() instanceof String
                        && "piko_import_session".equals(content.getChildAt(i).getTag())) {
                    return;
                }
            }

            // Re-check logged-out: the user may have logged in meanwhile.
            if (!SessionBackup.isLoggedOut(activity)) {
                return;
            }

            TextView pill = new TextView(activity);
            pill.setTag("piko_import_session");
            pill.setText(str("piko_import_session"));
            pill.setAllCaps(false);
            pill.setTextSize(15);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(activity, 24), dp(activity, 12), dp(activity, 24), dp(activity, 12));

            int fg = UI.getThemedColour("igds_color_primary_text");
            pill.setTextColor(fg);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(UI.getThemedColour("igds_color_primary_background"));
            bg.setStroke(dp(activity, 1), UI.getThemedColour("igds_color_secondary_text"));
            bg.setCornerRadius(dp(activity, 24));
            pill.setBackground(bg);

            pill.setOnClickListener(v -> {
                try {
                    android.content.Intent intent =
                            new android.content.Intent(v.getContext(), RestoreSessionActivity.class);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    v.getContext().startActivity(intent);
                } catch (Exception e) {
                    Logger.printException(() -> "LoginScreenImportButton click failed", e);
                    Utils.showToastShort("Import failed to open");
                }
            });

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            // Hug the bottom edge but stay inside the frame / clear of the
            // gesture bar; below the stock login buttons, which sit higher.
            lp.leftMargin = dp(activity, 24);
            lp.rightMargin = dp(activity, 24);
            lp.bottomMargin = dp(activity, 16);
            lp.topMargin = 0;
            content.addView(pill, lp);
            pill.bringToFront();
        } catch (Exception e) {
            Logger.printException(() -> "LoginScreenImportButton.addImportButton failed", e);
        }
    }

    private static int dp(Activity ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
