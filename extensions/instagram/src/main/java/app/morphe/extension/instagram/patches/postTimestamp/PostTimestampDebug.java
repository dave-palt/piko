/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.postTimestamp;

import app.morphe.extension.shared.Logger;

/** Diagnostic: logs once when the timestamp gate evaluates true. */
public final class PostTimestampDebug {

    private static volatile boolean logged;

    public static void logGateOnce() {
        if (logged) return;
        logged = true;
        Logger.printInfo(() -> "TIMESTAMP GATE PASSED (Pref.showPostTimestamp()=true)");
    }

    private static volatile boolean loggedFalse;

    /** Diagnostic: logs once when the timestamp gate evaluates false, with both inputs. */
    public static void logGateFalseOnce(boolean sharedPrefValue, boolean settingsStatusValue) {
        if (loggedFalse) return;
        loggedFalse = true;
        Logger.printInfo(() -> "TIMESTAMP GATE FALSE: sharedPref=" + sharedPrefValue + " settingsStatus=" + settingsStatusValue);
    }

    private PostTimestampDebug() {}
}
