/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.sessionbackup

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.patcher.patch.bytecodePatch

@Suppress("unused")
val sessionBackupPatch =
    bytecodePatch(
        name = "Import/Export session",
        description =
            "Adds backup and restore of the login session to piko settings. " +
                "This is useful when re-installing piko or installing it as a clone.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        dependsOn(
            settingsPatch,
        )

        execute {
            // Extension-only patch: the export/import UI lives in the piko
            // settings About section (ButtonPref keys registered statically in
            // ButtonPref.java / ActivityHook.java) and the backup/restore
            // activities are added to the manifest by addSettingsActivityPatch.
            // No bytecode injection is needed; the extension reads/writes IG's
            // AuthHeaderPrefs via reflection at runtime.
        }
    }
