/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.links.copyMediaLink

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.SHARE_SHEET_COPY_LINK_CLASS
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.Opcode

internal object DirectShareSheetOnViewCreatedFingerprint : Fingerprint(
    definingClass = "Linstagram/features/direct/fragment/sharesheet/DirectShareSheetFragment;",
    name = "onViewCreated",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
    returnType = "V",
)

@Suppress("unused")
val shareSheetCopyLinkPatch =
    bytecodePatch(
        name = "Copy link row on share sheet",
        description =
            "Adds copy buttons to the bottom of the direct share sheet: two side-by-side buttons (copy current / copy all) for gallery posts, a single copy-link button otherwise",
        default = true,
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            DirectShareSheetOnViewCreatedFingerprint.method.apply {
                val superCallIndex = indexOfFirstInstruction(Opcode.INVOKE_SUPER)
                if (superCallIndex < 0) {
                    throw PatchException("invoke-super not found in DirectShareSheetFragment.onViewCreated")
                }

                // invoke-super {this, view, bundle}
                val superRegisters = instructions[superCallIndex].registersUsed
                val thisRegister = superRegisters[0]
                val viewRegister = superRegisters[1]

                addInstructions(
                    superCallIndex + 1,
                    """
                    invoke-static {v$thisRegister, v$viewRegister}, $SHARE_SHEET_COPY_LINK_CLASS->addCopyLinkRow(Ljava/lang/Object;Landroid/view/View;)V
                    """.trimIndent(),
                )

                enableSettings("shareSheetCopyLink")
            }
        }
    }
