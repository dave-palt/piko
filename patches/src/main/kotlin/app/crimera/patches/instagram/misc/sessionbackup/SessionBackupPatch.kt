/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.sessionbackup

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "$PATCHES_DESCRIPTOR/sessionbackup/LoginScreenImportButton;"

// com.instagram.modal.ModalActivity#onCreate — the login screen host.
// Anchor "ModalActivity.onCreate" is unique app-wide (verified on 435).
internal object ModalActivityOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    definingClass = "Lcom/instagram/modal/ModalActivity;",
    strings = listOf("ModalActivity.onCreate"),
)

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
            // Add the "Import login session" pill to the login screen
            // (ModalActivity) so a fresh install can restore a session
            // without reaching piko settings. Inject before the final
            // return-void (main success path; early exception returns skip).
            ModalActivityOnCreateFingerprint.method.apply {
                val returnIndex =
                    instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
                require(returnIndex >= 0) { "ModalActivity.onCreate: no return-void found" }

                addInstructions(
                    returnIndex,
                    """
                    invoke-static {p0}, $EXTENSION_CLASS_DESCRIPTOR->addImportButton(Landroid/app/Activity;)V
                    """.trimIndent(),
                )
            }
        }
    }
