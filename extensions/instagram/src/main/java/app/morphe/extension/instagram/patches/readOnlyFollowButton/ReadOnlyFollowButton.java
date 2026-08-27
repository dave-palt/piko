package app.morphe.extension.instagram.patches.readOnlyFollowButton;

import android.view.View;

import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;

/**
 * Runtime helper for the read-only follow button patch.
 *
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
public final class ReadOnlyFollowButton {

    private static Object function0Noop;

    /**
     * Compose sites (Vgs.A01 / WkD.A02) pass their onClick Function0 through
     * here; when the pref is on a cached no-op lambda is returned instead.
     * Built via a dynamic proxy because extension Java compiles against
     * stubs only (no kotlin-stdlib). Always returns a valid Function0.
     */
    public static Object noop(Object original) {
        try {
            if (original != null && Pref.readOnlyFollowButton()) {
                synchronized (ReadOnlyFollowButton.class) {
                    if (function0Noop == null) {
                        Class<?> fn0 = Class.forName("kotlin.jvm.functions.Function0");
                        function0Noop = java.lang.reflect.Proxy.newProxyInstance(
                                fn0.getClassLoader(), new Class[]{fn0},
                                (proxy, method, args) -> null);
                    }
                }
                return function0Noop;
            }
        } catch (Throwable t) {
            Logger.printException(() -> "ReadOnlyFollowButton noop failure", t);
        }
        return original;
    }

    private static final View.OnClickListener NOOP_LISTENER = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            Logger.printInfo(() -> "ReadOnlyFollowButton: click neutered (read-only indicator)");
        }
    };

    /**
     * Swaps a follow click listener for a no-op when the read-only pref is on.
     * Returns the original when the pref is off or on any failure, so the
     * caller always receives a valid OnClickListener.
     */
    public static View.OnClickListener noopListener(View.OnClickListener original) {
        try {
            if (original != null && Pref.readOnlyFollowButton()) {
                return NOOP_LISTENER;
            }
        } catch (Throwable t) {
            Logger.printException(() -> "ReadOnlyFollowButton noop failure", t);
        }
        return original;
    }
}
